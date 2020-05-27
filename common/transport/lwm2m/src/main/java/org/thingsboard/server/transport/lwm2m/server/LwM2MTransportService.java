/**
 * Copyright © 2016-2020 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.transport.lwm2m.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.node.LwM2mNode;
import org.eclipse.leshan.core.node.LwM2mObject;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.request.ContentFormat;
import org.eclipse.leshan.core.request.DiscoverRequest;
import org.eclipse.leshan.core.response.DiscoverResponse;
import org.eclipse.leshan.core.response.LwM2mResponse;
import org.eclipse.leshan.core.response.ObserveResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.server.californium.LeshanServer;
import org.eclipse.leshan.server.registration.Registration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.common.transport.adaptor.AdaptorException;
import org.thingsboard.server.common.transport.service.DefaultTransportService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.transport.lwm2m.server.adaptors.JsonLwM2MAdaptor;
import org.thingsboard.server.transport.lwm2m.server.adaptors.ReadResult;


import java.util.Collection;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.mqtt.MqttConnectReturnCode.*;
import static org.thingsboard.server.transport.lwm2m.server.adaptors.LwM2MProvider.*;

@Service("LwM2MTransportService")
@ConditionalOnExpression("'${service.type:null}'=='tb-transport' || ('${service.type:null}'=='monolith' && '${transport.lwm2m.enabled}'=='true')")
@Slf4j
public class LwM2MTransportService {
    //    private LwM2MTransportAdaptor adaptor;
    private final UUID sessionId = UUID.randomUUID();
    protected final Map<String /* clientEndPoint */, TransportProtos.LwM2MResponseMsg> sessions = new ConcurrentHashMap<>();

    @Autowired
    private JsonLwM2MAdaptor adaptor;

    @Autowired
    private TransportService transportService;

    @Autowired
    private LeshanServer lwServer;

    @Autowired
    private LwM2MTransportCtx context;

    @Autowired
    private LwM2MTransportRequest lwM2MTransportRequest;

    public void onRegistered(Registration registration) {
        String endpointId = registration.getEndpoint();
        String smsNumber = registration.getSmsNumber() == null ? "" : registration.getSmsNumber();
        String lwm2mVersion = registration.getLwM2mVersion();

        log.info("[{}] [{}] Received endpoint registration version event", endpointId, lwm2mVersion);
//        String jReg = this.gson.toJson(registration);
        TransportProtos.LwM2MRegistrationRequestMsg registrationRequestMsg = TransportProtos.LwM2MRegistrationRequestMsg.newBuilder()
                .setTenantId(context.getTenantId())
                .setEndpoint(endpointId)
                .setLwM2MVersion(lwm2mVersion)
                .setSmsNumber(smsNumber).build();
        TransportProtos.LwM2MRequestMsg requestMsg = TransportProtos.LwM2MRequestMsg.newBuilder().setRegistrationMsg(registrationRequestMsg).build();
        context.getTransportService().process(requestMsg, new TransportServiceCallback<TransportProtos.LwM2MResponseMsg>() {
            @Override
            public void onSuccess(TransportProtos.LwM2MResponseMsg msg) {
                log.info("[{}][{}] Received endpoint registration response: {}", lwm2mVersion, endpointId, msg);
                sessions.put(endpointId, msg);
                //Tests.
//                Collection<Registration> registrations = lwM2MTransportRequest.doGetRegistrations();
//                log.info("Ok process get registrations: [{}]", registrations);
//                String traget = "/3";
//                String typeOper = GET_TYPE_OPER_READ;
//                LwM2mResponse cResponse =  lwM2MTransportRequest.doGet(endpointId,  traget, typeOper, ContentFormat.TLV.getName());
//                log.info("[{}] [{}] [{}] Ok process get request: [{}]", endpointId, traget, typeOper, cResponse);
//                traget = "/3/0";
//                cResponse =  lwM2MTransportRequest.doGet(endpointId,  traget, typeOper, ContentFormat.TLV.getName());
//                log.info("[{}] [{}] [{}] Ok process get request: [{}]", endpointId, traget, typeOper, cResponse);
//                traget = "/3/0/14";
//                typeOper = GET_TYPE_OPER_READ;
//                cResponse =  lwM2MTransportRequest.doGet(endpointId,  traget, typeOper, ContentFormat.TLV.getName());
//                log.info("[{}] [{}] [{}] Ok process get request: [{}]", endpointId, traget, typeOper, cResponse);
//                traget = "/3/0/14";
//                typeOper = GET_TYPE_OPER_DISCOVER;
//                cResponse =  lwM2MTransportRequest.doGet(endpointId,  traget, typeOper, ContentFormat.TLV.getName());
//                log.info("[{}] [{}] [{}] Ok process get request: [{}]", endpointId, traget, typeOper, cResponse);
                ReadResult readResult = doGetAttributsTelemetry(endpointId);
                processDevicePublish(readResult.getPostAttribute(), DEVICE_ATTRIBUTES_TOPIC, -1, endpointId);
                processDevicePublish(readResult.getPostTelemetry(), DEVICE_TELEMETRY_TOPIC, -1, endpointId);
            }

            @Override
            public void onError(Throwable e) {
                log.warn("[{}][{}] Failed to process registration request [{}]", lwm2mVersion, endpointId, e);
            }
        });
    }

    public void processDevicePublish(JsonElement msg, String topicName, int msgId, String clientEndpoint) {
        TransportProtos.SessionInfoProto sessionInfo = getValidateSessionInfo(clientEndpoint);
        if (sessionInfo != null) {
            try {
                if (topicName.equals(DEVICE_TELEMETRY_TOPIC)) {
                    TransportProtos.PostTelemetryMsg postTelemetryMsg = adaptor.convertToPostTelemetry(msg);
                    transportService.process(sessionInfo, postTelemetryMsg, getPubAckCallback(msgId, postTelemetryMsg));
                } else if (topicName.equals(DEVICE_ATTRIBUTES_TOPIC)) {
                    TransportProtos.PostAttributeMsg postAttributeMsg = adaptor.convertToPostAttributes(msg);
                    transportService.process(sessionInfo, postAttributeMsg, getPubAckCallback(msgId, postAttributeMsg));
                }
//            else if (topicName.startsWith(MqttTopics.DEVICE_ATTRIBUTES_REQUEST_TOPIC_PREFIX)) {
//                TransportProtos.GetAttributeRequestMsg getAttributeMsg = adaptor.convertToGetAttributes(deviceSessionCtx, mqttMsg);
//                transportService.process(sessionInfo, getAttributeMsg, getPubAckCallback(ctx, msgId, getAttributeMsg));
//            } else if (topicName.startsWith(MqttTopics.DEVICE_RPC_RESPONSE_TOPIC)) {
//                TransportProtos.ToDeviceRpcResponseMsg rpcResponseMsg = adaptor.convertToDeviceRpcResponse(deviceSessionCtx, mqttMsg);
//                transportService.process(sessionInfo, rpcResponseMsg, getPubAckCallback(ctx, msgId, rpcResponseMsg));
//            } else if (topicName.startsWith(MqttTopics.DEVICE_RPC_REQUESTS_TOPIC)) {
//                TransportProtos.ToServerRpcRequestMsg rpcRequestMsg = adaptor.convertToServerRpcRequest(deviceSessionCtx, mqttMsg);
//                transportService.process(sessionInfo, rpcRequestMsg, getPubAckCallback(ctx, msgId, rpcRequestMsg));
//            } else if (topicName.equals(MqttTopics.DEVICE_CLAIM_TOPIC)) {
//                TransportProtos.ClaimDeviceMsg claimDeviceMsg = adaptor.convertToClaimDevice(deviceSessionCtx, mqttMsg);
//                transportService.process(sessionInfo, claimDeviceMsg, getPubAckCallback(ctx, msgId, claimDeviceMsg));
//            } else {
//                transportService.reportActivity(sessionInfo);
//            }
            } catch (AdaptorException e) {
                log.warn("[{}] Failed to process publish msg [{}][{}]", sessionId, topicName, msgId, e);
                log.info("[{}] Closing current session due to invalid publish msg [{}][{}]", sessionId, topicName, msgId);

            }
        }
    }

    private <T> TransportServiceCallback<Void> getPubAckCallback(final int msgId, final T msg) {
        return new TransportServiceCallback<Void>() {
            @Override
            public void onSuccess(Void dummy) {
                log.trace("[{}] Published msg: {}", sessionId, msg);
            }

            @Override
            public void onError(Throwable e) {
                log.trace("[{}] Failed to publish msg: {}", sessionId, msg, e);

            }
        };
    }

    private TransportProtos.SessionInfoProto getValidateSessionInfo(String endpointId) {
        TransportProtos.SessionInfoProto sessionInfo = null;
        TransportProtos.LwM2MResponseMsg msg = sessions.get(endpointId);
        if (msg == null || !msg.getRegistrationMsg().hasDeviceInfo()) {
            log.warn(CONNECTION_REFUSED_NOT_AUTHORIZED.toString());
        } else {
            sessionInfo = TransportProtos.SessionInfoProto.newBuilder()
                    .setNodeId(context.getNodeId())
                    .setSessionIdMSB(sessionId.getMostSignificantBits())
                    .setSessionIdLSB(sessionId.getLeastSignificantBits())
                    .setDeviceIdMSB(msg.getRegistrationMsg().getDeviceInfo().getDeviceIdMSB())
                    .setDeviceIdLSB(msg.getRegistrationMsg().getDeviceInfo().getDeviceIdLSB())
                    .setTenantIdMSB(msg.getRegistrationMsg().getDeviceInfo().getTenantIdMSB())
                    .setTenantIdLSB(msg.getRegistrationMsg().getDeviceInfo().getTenantIdLSB())
                    .setDeviceName(msg.getRegistrationMsg().getDeviceInfo().getDeviceName())
                    .setDeviceType(msg.getRegistrationMsg().getDeviceInfo().getDeviceType())
                    .build();
            transportService.process(sessionInfo, DefaultTransportService.getSessionEventMsg(TransportProtos.SessionEvent.OPEN), null);
            log.info("[{}] Client connected!", sessionId);
        }
        return sessionInfo;
    }

    public void updatedReg(Registration registration) {
        String endpointId = registration.getEndpoint();
        String smsNumber = registration.getSmsNumber() == null ? "" : registration.getSmsNumber();
        String lwm2mVersion = registration.getLwM2mVersion();
        log.info("[{}] [{}] Received endpoint updated registration version event", endpointId, lwm2mVersion);
        //TODO: associate endpointId with device information.
    }

    public void unReg(Registration registration) {
        String endpointId = registration.getEndpoint();
        String smsNumber = registration.getSmsNumber() == null ? "" : registration.getSmsNumber();
        String lwm2mVersion = registration.getLwM2mVersion();
        log.info("[{}] [{}] Received endpoint un registration version event", endpointId, lwm2mVersion);
        //TODO: associate endpointId with device informat

    }

    public void onSleepingDev(Registration registration) {
        String endpointId = registration.getEndpoint();
        String smsNumber = registration.getSmsNumber() == null ? "" : registration.getSmsNumber();
        String lwm2mVersion = registration.getLwM2mVersion();
        log.info("[{}] [{}] Received endpoint Sleeping version event", endpointId, lwm2mVersion);
        //TODO: associate endpointId with device information.
    }

    public void onAwakeDev(Registration registration) {
        String endpointId = registration.getEndpoint();
        String smsNumber = registration.getSmsNumber() == null ? "" : registration.getSmsNumber();
        String lwm2mVersion = registration.getLwM2mVersion();
        log.info("[{}] [{}] Received endpoint Awake version event", endpointId, lwm2mVersion);
        //TODO: associate endpointId with device information.
    }

    public void observOnResponse(Observation observation, Registration registration, ObserveResponse response) {
//        String data = new StringBuilder("{\"ep\":\"").append(registration.getEndpoint()).append("\",\"res\":\"")
//                .append(observation.getPath().toString()).append("\",\"val\":")
//                .append(this.gson.toJson(response.getContent())).append("}").toString();
//                sendEvent(EVENT_NOTIFICATION, data, registration.getEndpoint());
        //TODO: associate endpointId with device information.
    }

    //    // /clients/endPoint/LWRequest/discover : do LightWeight M2M discover request on a given client.
    public DiscoverResponse getDiscover(String target, String clientEndpoint, String timeoutParam) throws InterruptedException {
        DiscoverRequest request = new DiscoverRequest(target);
        return this.lwServer.send(getRegistration(clientEndpoint), request, this.context.getTimeout());
    }

    public Registration getRegistration(String clientEndpoint) {
        return this.lwServer.getRegistrationService().getByEndpoint(clientEndpoint);
    }

    @SneakyThrows
    public ReadResult doGetAttributsTelemetry(String clientEndpoint) {
        ReadResult readResult = new ReadResult();
        Registration registration = lwServer.getRegistrationService().getByEndpoint(clientEndpoint);
        registration.getAdditionalRegistrationAttributes().entrySet().forEach(entry -> {
            log.info("Attributes: Key : [{}] Value : [{}]", entry.getKey() , entry.getValue());
            readResult.getPostAttribute().addProperty(entry.getKey(), entry.getValue());
        });
        lwServer.getModelProvider().getObjectModel(registration).getObjectModels().forEach(om -> {
            String idObj = String.valueOf(om.id);
            LwM2mResponse cResponse = lwM2MTransportRequest.doGet(clientEndpoint, "/" + idObj, GET_TYPE_OPER_READ, ContentFormat.TLV.getName());
            if (cResponse != null) {
                LwM2mNode content = ((ReadResponse) cResponse).getContent();
                ((LwM2mObject) content).getInstances().entrySet().stream().forEach(instance -> {
                    String instanceId = String.valueOf(instance.getValue().getId());
                    instance.getValue().getResources().entrySet().stream().forEach(resource -> {
                        int resourceId = resource.getValue().getId();
                        String resourceValue = getResourceValueToString(resource.getValue());
                        String attrTelName = om.name + "_" + instanceId + "_" + om.resources.get(resourceId).name;
                        log.info("resource.getValue() [{}] : [{}] -> [{}]", attrTelName, resourceValue, om.resources.get(resourceId).operations.name());
                        if ((om.resources.get(resourceId).operations.name().equals("R"))) {
                            readResult.getPostAttribute().addProperty(attrTelName, resourceValue);
                        } else {
                            readResult.getPostTelemetry().addProperty(attrTelName, resourceValue);
                        }
                    });
                });
            }
        });
        return readResult;
    }

    private String getResourceValueToString(LwM2mResource resource) {
        Object resValue;
        try {
            resValue = resource.getValues();
        } catch (NoSuchElementException e) {
            resValue = resource.getValue();
        }
        return String.valueOf(resValue);
    }

    // Get Model for this registration
//    public Collection<ObjectModel> getObjectsModel(String clientEndpoint) {
//        Registration registration = lwServer.getRegistrationService().getByEndpoint(clientEndpoint);
//        return lwServer.getModelProvider().getObjectModel(registration).getObjectModels();
//    }

}
