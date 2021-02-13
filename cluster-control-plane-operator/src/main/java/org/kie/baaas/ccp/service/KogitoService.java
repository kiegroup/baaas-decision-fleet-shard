/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kie.baaas.ccp.service;

import java.io.IOException;
import java.io.StringReader;
import java.util.Objects;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.kie.baaas.ccp.api.Decision;
import org.kie.baaas.ccp.api.DecisionVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.kie.baaas.ccp.api.DecisionVersionStatus.CONDITION_BUILD;
import static org.kie.baaas.ccp.api.DecisionVersionStatus.REASON_FAILED;
import static org.kie.baaas.ccp.controller.DecisionLabels.BAAAS_RESOURCE_KOGITO_SERVICE;
import static org.kie.baaas.ccp.controller.DecisionLabels.BAAAS_RESOURCE_LABEL;
import static org.kie.baaas.ccp.controller.DecisionLabels.CUSTOMER_LABEL;
import static org.kie.baaas.ccp.controller.DecisionLabels.DECISION_LABEL;
import static org.kie.baaas.ccp.controller.DecisionLabels.DECISION_VERSION_LABEL;
import static org.kie.baaas.ccp.controller.DecisionLabels.MANAGED_BY_LABEL;
import static org.kie.baaas.ccp.controller.DecisionLabels.OPERATOR_NAME;
import static org.kie.baaas.ccp.service.JsonResourceUtils.getConditionStatus;
import static org.kie.baaas.ccp.service.JsonResourceUtils.getSpec;

@ApplicationScoped
public class KogitoService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KogitoService.class);

    private static final int REPLICAS = 1;
    private static final String BAAAS_KAFKA_BOOTSTRAP_SERVERS = "BAAAS_KAFKA_BOOTSTRAP_SERVERS";
    private static final String BAAAS_KAFKA_CLIENTID = "BAAAS_KAFKA_CLIENTID";
    private static final String BAAAS_KAFKA_CLIENTSECRET = "BAAAS_KAFKA_CLIENTSECRET";
    private static final String BAAAS_KAFKA_INCOMING_TOPIC = "BAAAS_KAFKA_INCOMING_TOPIC";
    private static final String BAAAS_KAFKA_OUTGOING_TOPIC = "BAAAS_KAFKA_OUTGOING_TOPIC";


    public static final CustomResourceDefinitionContext KOGITO_RUNTIME_CONTEXT = new CustomResourceDefinitionContext
            .Builder()
            .withGroup("app.kiegroup.org")
            .withVersion("v1beta1")
            .withName("kogitoruntimes.app.kiegroup.org")
            .withKind("KogitoRuntime")
            .withPlural("kogitoruntimes")
            .withScope("Namespaced")
            .build();

    @Inject
    KubernetesClient client;

    @Inject
    DecisionVersionService versionService;

    public static String getServiceName(DecisionVersion version) {
        return version.getMetadata().getLabels().get(DECISION_LABEL);
    }

    public static String getServiceUrl(DecisionVersion version) {
        return "http://" + getServiceName(version) + ":8080";
    }

    public static JsonObject buildService(DecisionVersion version) {
        JsonObjectBuilder specBuilder = Json.createObjectBuilder()
                .add("image", version.getStatus().getImageRef())
                .add("replicas", REPLICAS);
        if (version.getSpec().getKafka() != null) {
            JsonArrayBuilder envBuilder = Json.createArrayBuilder();
            specBuilder.add("propertiesConfigMap", version.getMetadata().getName());
            envBuilder
                    .add(JsonResourceUtils.buildEnvValueFromSecret(
                            BAAAS_KAFKA_CLIENTID,
                            "clientid",
                            getKafkaSecretName(version)))
                    .add(JsonResourceUtils.buildEnvValueFromSecret(
                            BAAAS_KAFKA_CLIENTSECRET,
                            "clientsecret",
                            getKafkaSecretName(version)))
                    .add(JsonResourceUtils.buildEnvValue(
                            BAAAS_KAFKA_BOOTSTRAP_SERVERS,
                            version.getSpec().getKafka().getBootstrapServers()));
            if (version.getSpec().getKafka().getInputTopic() != null) {
                envBuilder.add(JsonResourceUtils.buildEnvValue(
                        BAAAS_KAFKA_INCOMING_TOPIC,
                        version.getSpec().getKafka().getInputTopic()));
            }
            if (version.getSpec().getKafka().getInputTopic() != null) {
                envBuilder.add(JsonResourceUtils.buildEnvValue(
                        BAAAS_KAFKA_OUTGOING_TOPIC,
                        version.getSpec().getKafka().getOutputTopic()));
            }
            specBuilder.add("env", envBuilder.build());
        }
        //Kogito Operator requires to own the KogitoRuntime resource.
        version.getMetadata().getOwnerReferences().get(0).setController(false);
        JsonArrayBuilder ownerRefs = Json.createArrayBuilder(Json.createReader(new StringReader(Serialization.asJson(version.getMetadata().getOwnerReferences()))).readArray());
        return Json.createObjectBuilder()
                .add("apiVersion", KOGITO_RUNTIME_CONTEXT.getGroup() + "/" + KOGITO_RUNTIME_CONTEXT.getVersion())
                .add("kind", KOGITO_RUNTIME_CONTEXT.getKind())
                .add("metadata", Json.createObjectBuilder()
                        .add("name", getServiceName(version))
                        .add("namespace", version.getMetadata().getNamespace())
                        .add("labels", Json.createObjectBuilder()
                                .add(BAAAS_RESOURCE_LABEL, BAAAS_RESOURCE_KOGITO_SERVICE)
                                .add(DECISION_VERSION_LABEL, version.getMetadata().getName())
                                .add(DECISION_LABEL, version.getMetadata().getLabels().get(DECISION_LABEL))
                                .add(CUSTOMER_LABEL, version.getMetadata().getLabels().get(CUSTOMER_LABEL))
                                .add(MANAGED_BY_LABEL, OPERATOR_NAME)
                                .build())
                        .add("ownerReferences", ownerRefs)
                        .build())
                .add("spec", specBuilder.build())
                .build();
    }

    public ConfigMap createKafkaConfig(DecisionVersion version) {
        ConfigMap expected = client.configMaps()
                .load(this.getClass().getClassLoader().getResourceAsStream("deployment/kafka-application-config.yaml"))
                .get();
        expected.setMetadata(new ObjectMetaBuilder()
                .withName(version.getMetadata().getName())
                .addToLabels(DECISION_LABEL, version.getMetadata().getLabels().get(DECISION_LABEL))
                .addToLabels(DECISION_VERSION_LABEL, version.getMetadata().getName())
                .addToLabels(MANAGED_BY_LABEL, OPERATOR_NAME)
                .withOwnerReferences(version.getOwnerReference())
                .build());
        ConfigMap current = client.configMaps()
                .inNamespace(version.getMetadata().getNamespace())
                .withName(version.getMetadata().getName())
                .get();
        if (current != null && Objects.equals(expected.getData(), current.getData())) {
            LOGGER.debug("Using existing Kafka Config for version {}: {}", version.getMetadata().getName(), current);
            return current;
        }
        LOGGER.debug("Creating or updating Kafka Config for version {}: {}", version.getMetadata().getName(), expected);
        return client.configMaps()
                .inNamespace(version.getMetadata().getNamespace())
                .createOrReplace(expected);
    }

    public void createOrUpdateService(DecisionVersion version) {
        if (!isBuilt(version) || !isCurrent(version)) {
            return;
        }
        LOGGER.info("Creating Kogito Runtime for version {}", version.getMetadata().getName());
        JsonObject expected = buildService(version);
        if (version.getSpec().getKafka() != null) {
            createKafkaAuthSecret(version);
            if (version.getSpec().getKafka().getInputTopic() != null) {
                ConfigMap kafkaConfig = createKafkaConfig(version);
                version.getStatus().setConfigRef(kafkaConfig.getMetadata().getName());
            }
        }
        String name = JsonResourceUtils.getName(expected);
        JsonObject current = null;
        try {
            current = Json.createObjectBuilder(client.customResource(KOGITO_RUNTIME_CONTEXT)
                    .get(version.getMetadata().getNamespace(), version.getMetadata().getLabels().get(DECISION_LABEL)))
                    .build();
        } catch (KubernetesClientException e) {
            LOGGER.debug("KogitoRuntime {} does not exist. Creating...", name);
        }
        if (needsUpdate(expected, current)) {
            try {
                client.customResource(KOGITO_RUNTIME_CONTEXT)
                        .createOrReplace(version.getMetadata().getNamespace(), expected.toString());
                version.getStatus().setKogitoServiceRef(name);
            } catch (IOException e) {
                LOGGER.warn("Unable to process KogitoService", e);
                versionService.setServiceStatus(version, Boolean.FALSE, REASON_FAILED, e.getMessage());
            }
        }
        boolean provisioning = getConditionStatus(current, "Provisioning");
        boolean deployed = getConditionStatus(current, "Deployed");
        String reason = provisioning ? "Provisioning" : "Unknown";
        Boolean status = Boolean.FALSE;
        if (deployed) {
            reason = "Deployed";
            status = Boolean.TRUE;
            versionService.setReadyStatus(version);
        }
        versionService.setServiceStatus(version, status, reason, "");
    }

    private boolean isBuilt(DecisionVersion version) {
        return version.getStatus() != null
                && version.getStatus().getCondition(CONDITION_BUILD) != null
                && Boolean.parseBoolean(version.getStatus().getCondition(CONDITION_BUILD).getStatus());
    }

    private boolean isCurrent(DecisionVersion version) {
        Decision decision = client.customResources(Decision.class)
                .inNamespace(version.getMetadata().getNamespace())
                .withName(version.getMetadata().getLabels().get(DECISION_LABEL))
                .get();
        return decision != null
                && Objects.equals(decision.getSpec().getDefinition().getVersion(), version.getSpec().getVersion());
    }

    private boolean needsUpdate(JsonObject expected, JsonObject current) {
        if (current == null) {
            return true;
        }
        JsonObject expectedSpec = getSpec(expected);
        JsonObject currentSpec = getSpec(current);
        return !expectedSpec.getString("image").equals(currentSpec.getString("image"))
                || expectedSpec.getInt("replicas") != currentSpec.getInt("replicas");
    }

    private static String getKafkaSecretName(DecisionVersion version) {
        return version.getMetadata().getName() + "-kafka-auth";
    }

    private void createKafkaAuthSecret(DecisionVersion version) {
        Secret current = client.secrets()
                .inNamespace(version.getMetadata().getNamespace())
                .withName(getKafkaSecretName(version))
                .get();
        // TODO: Replace how credentials are retrieved from a secure vault. For the demo will be a pre-provisioned secret.
        Secret vault = client.secrets()
                .inNamespace(client.getNamespace())
                .withName(version.getSpec().getKafka().getSecretName())
                .get();
        if (vault == null) {
            LOGGER.error("Missing required kafka-auth secret {} in {}", version.getSpec().getKafka().getSecretName(), client.getNamespace());
            return;
        }
        if (current == null || !Objects.equals(current.getData(), vault.getData())) {
            Secret expected = new SecretBuilder()
                    .withMetadata(new ObjectMetaBuilder()
                            .withNamespace(version.getMetadata().getNamespace())
                            .withName(getKafkaSecretName(version))
                            .addToLabels(DECISION_VERSION_LABEL, version.getMetadata().getName())
                            .addToLabels(DECISION_LABEL, version.getMetadata().getLabels().get(DECISION_LABEL))
                            .withOwnerReferences(version.getOwnerReference())
                            .build())
                    .withData(vault.getData())
                    .build();
            LOGGER.debug("Create or replace kafka-auth secret {} in {}", expected.getMetadata().getName(), expected.getMetadata().getNamespace());
            client.secrets().inNamespace(version.getMetadata().getNamespace()).createOrReplace(expected);
        }
    }
}
