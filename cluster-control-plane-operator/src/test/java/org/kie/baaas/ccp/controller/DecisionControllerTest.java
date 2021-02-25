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
package org.kie.baaas.ccp.controller;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObject;

import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.kie.baaas.ccp.api.Decision;
import org.kie.baaas.ccp.api.DecisionBuilder;
import org.kie.baaas.ccp.api.DecisionSpec;
import org.kie.baaas.ccp.api.DecisionStatus;
import org.kie.baaas.ccp.api.DecisionVersion;
import org.kie.baaas.ccp.api.DecisionVersionBuilder;
import org.kie.baaas.ccp.api.DecisionVersionSpec;
import org.kie.baaas.ccp.api.DecisionVersionStatus;
import org.kie.baaas.ccp.api.Phase;
import org.kie.baaas.ccp.api.ResourceUtils;
import org.kie.baaas.ccp.service.JsonResourceUtils;
import org.kie.baaas.ccp.service.KogitoService;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.kie.baaas.ccp.api.DecisionVersionStatus.CONDITION_BUILD;
import static org.kie.baaas.ccp.controller.DecisionLabels.CUSTOMER_LABEL;
import static org.kie.baaas.ccp.controller.DecisionLabels.DECISION_LABEL;
import static org.kie.baaas.ccp.controller.DecisionLabels.MANAGED_BY_LABEL;
import static org.kie.baaas.ccp.controller.DecisionLabels.OPERATOR_NAME;
import static org.kie.baaas.ccp.service.KogitoService.KOGITO_RUNTIME_CONTEXT;

@QuarkusTest
class DecisionControllerTest extends AbstractControllerTest {

    @Inject
    DecisionController decisionController;

    @Test
    void testCreateNewVersion() {
        //Given
        Decision decision = new DecisionBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("some-decision")
                        .withNamespace(CUSTOMER_NS)
                        .addToLabels(CUSTOMER_LABEL, CUSTOMER)
                        .build())
                .withSpec(new DecisionSpec()
                        .setDefinition(new DecisionVersionSpec()
                                .setSource(URI.create("somesource"))
                                .setVersion("1")))
                .build();

        //When
        UpdateControl<Decision> updateControl = decisionController.createOrUpdateResource(decision, null);

        //Then
        assertThat(updateControl.isUpdateStatusSubResource(), notNullValue());
        DecisionVersion version = client.customResources(DecisionVersion.class).inNamespace(CUSTOMER_NS).withName("some-decision-1").get();
        assertThat(version.getMetadata().getName(), is("some-decision-1"));
        Map<String, String> expectedLabels = Map.of(DECISION_LABEL, decision.getMetadata().getName(), MANAGED_BY_LABEL, OPERATOR_NAME, CUSTOMER_LABEL, CUSTOMER);
        expectedLabels.forEach((key, value) -> assertThat(version.getMetadata().getLabels(), hasEntry(key, value)));
        assertThat(version.getMetadata().getOwnerReferences().size(), is(1));
        assertThat(version.getMetadata().getOwnerReferences().get(0).getName(), is(decision.getMetadata().getName()));
        assertThat(version.getSpec(), is(decision.getSpec().getDefinition()));
        assertThat(version.getStatus().isReady(), is("False"));
    }

    @Test
    void testReplaceVersion() {
        //Given
        Decision decision = new DecisionBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("some-decision")
                        .withNamespace(CUSTOMER_NS)
                        .addToLabels(CUSTOMER_LABEL, CUSTOMER)
                        .build())
                .withSpec(new DecisionSpec()
                        .setDefinition(new DecisionVersionSpec()
                                .setSource(URI.create("somesource"))
                                .setVersion("1")))
                .build();
        DecisionVersion previous = new DecisionVersionBuilder().withMetadata(new ObjectMetaBuilder()
                .withName("some-decision-1")
                .withNamespace(CUSTOMER_NS).build()).withStatus(new DecisionVersionStatus())
                .withStatus(new DecisionVersionStatus().setReady(Boolean.TRUE))
                .build();
        client.customResources(DecisionVersion.class).inNamespace(CUSTOMER_NS).create(previous);

        //When
        UpdateControl<Decision> updateControl = decisionController.createOrUpdateResource(decision, null);

        //Then
        assertThat(updateControl.isUpdateStatusSubResource(), is(false));
        DecisionVersion version = client.customResources(DecisionVersion.class).inNamespace(CUSTOMER_NS).withName("some-decision-1").get();
        assertThat(version.getMetadata().getName(), is("some-decision-1"));
        Map<String, String> expectedLabels = Map.of(DECISION_LABEL, decision.getMetadata().getName(), MANAGED_BY_LABEL, OPERATOR_NAME, CUSTOMER_LABEL, CUSTOMER);
        expectedLabels.forEach((key, value) -> assertThat(version.getMetadata().getLabels(), hasEntry(key, value)));
        assertThat(version.getMetadata().getOwnerReferences().size(), is(1));
        assertThat(version.getMetadata().getOwnerReferences().get(0).getName(), is(decision.getMetadata().getName()));
        assertThat(version.getSpec(), is(decision.getSpec().getDefinition()));
        assertThat(version.getStatus().isReady(), is("False"));
    }

    @Test
    void testChangeToMissingVersion() {
        //Given
        Decision decision = new DecisionBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("some-decision")
                        .withNamespace(CUSTOMER_NS)
                        .addToLabels(CUSTOMER_LABEL, CUSTOMER)
                        .build())
                .withSpec(new DecisionSpec()
                        .setDefinition(new DecisionVersionSpec()
                                .setSource(URI.create("somesource"))
                                .setVersion("1")))
                .build();
        client.customResources(Decision.class).inNamespace(CUSTOMER_NS).create(decision);
        DecisionVersion previous = new DecisionVersionBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("some-decision-1")
                        .withNamespace(CUSTOMER_NS)
                        .addToLabels(DECISION_LABEL, decision.getMetadata().getName())
                        .addToLabels(CUSTOMER_LABEL, decision.getMetadata().getLabels().get(CUSTOMER_LABEL))
                        .addToLabels(MANAGED_BY_LABEL, OPERATOR_NAME)
                        .withOwnerReferences(decision.getOwnerReference())
                        .build())
                .withStatus(new DecisionVersionStatus())
                .withSpec(decision.getSpec().getDefinition())
                .build();
        previous = client.customResources(DecisionVersion.class).inNamespace(CUSTOMER_NS).create(previous);
        previous.getStatus().setReady(Boolean.TRUE)
                .setCondition(CONDITION_BUILD, new ConditionBuilder().withType(CONDITION_BUILD).withStatus(ResourceUtils.capitalize(Boolean.TRUE)).build())
                .setImageRef("quay.io/baaas/test-some-decision:1");
        client.customResources(DecisionVersion.class).inNamespace(CUSTOMER_NS).updateStatus(previous);

        //When
        UpdateControl<Decision> updateControl = decisionController.createOrUpdateResource(decision, null);

        //Then
        assertThat(updateControl.isUpdateStatusSubResource(), is(false));
        DecisionVersion version = client.customResources(DecisionVersion.class).inNamespace(CUSTOMER_NS).withName("some-decision-1").get();
        JsonObject kogitoSvc = Json.createObjectBuilder(client.customResource(KOGITO_RUNTIME_CONTEXT).list(CUSTOMER_NS)).build().getJsonArray("items").getJsonObject(0);
        assertThat(kogitoSvc, notNullValue());
        assertThat(version.getMetadata().getName(), is("some-decision-1"));
        Map<String, String> expectedLabels = Map.of(DECISION_LABEL, decision.getMetadata().getName(), MANAGED_BY_LABEL, OPERATOR_NAME, CUSTOMER_LABEL, CUSTOMER);
        expectedLabels.forEach((key, value) -> assertThat(version.getMetadata().getLabels(), hasEntry(key, value)));
        assertThat(version.getMetadata().getOwnerReferences().size(), is(1));
        assertThat(version.getMetadata().getOwnerReferences().get(0).getName(), is(decision.getMetadata().getName()));
        assertThat(version.getSpec(), is(decision.getSpec().getDefinition()));
        assertThat(version.getStatus().isReady(), is("True"));
        assertThat(version.getStatus().getKogitoServiceRef(), is(JsonResourceUtils.getName(kogitoSvc)));
    }

    @Test
    void testReconcileReadyVersion() throws IOException {
        //Given
        Decision decision = new DecisionBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("some-decision")
                        .withNamespace(CUSTOMER_NS)
                        .addToLabels(CUSTOMER_LABEL, CUSTOMER)
                        .build())
                .withSpec(new DecisionSpec()
                        .setDefinition(new DecisionVersionSpec()
                                .setSource(URI.create("somesource"))
                                .setVersion("1")))
                .withStatus(new DecisionStatus()
                        .setVersionId("1")
                        .setEndpoint(URI.create("decision.kogito.svc")))
                .build();
        DecisionVersion previous = new DecisionVersionBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("some-decision-1")
                        .withNamespace(CUSTOMER_NS)
                        .withOwnerReferences(decision.getOwnerReference())
                        .addToLabels(DECISION_LABEL, decision.getMetadata().getName())
                        .addToLabels(CUSTOMER_LABEL, decision.getMetadata().getLabels().get(CUSTOMER_LABEL))
                        .addToLabels(MANAGED_BY_LABEL, OPERATOR_NAME)
                        .build())
                .withStatus(new DecisionVersionStatus()
                        .setReady(Boolean.TRUE)
                        .setImageRef("some-decision-image:latest")
                        .setKogitoServiceRef(decision.getMetadata().getName()))
                .withSpec(decision.getSpec().getDefinition())
                .build();
        client.namespaces().create(new NamespaceBuilder().withMetadata(new ObjectMetaBuilder().withName(CUSTOMER_NS).build()).build());
        client.customResource(KOGITO_RUNTIME_CONTEXT).create(CUSTOMER_NS, getKogitoRuntime(previous));
        client.customResources(DecisionVersion.class).inNamespace(CUSTOMER_NS).create(previous);

        //When
        UpdateControl<Decision> updateControl = decisionController.createOrUpdateResource(decision, null);

        //Then
        assertThat(updateControl.isUpdateStatusSubResource(), is(true));
        DecisionVersion version = client.customResources(DecisionVersion.class).inNamespace(CUSTOMER_NS).withName("some-decision-1").get();
        assertThat(version.getMetadata().getName(), is("some-decision-1"));
        Map<String, String> expectedLabels = Map.of(DECISION_LABEL, decision.getMetadata().getName(), MANAGED_BY_LABEL, OPERATOR_NAME, CUSTOMER_LABEL, CUSTOMER);
        expectedLabels.forEach((key, value) -> assertThat(version.getMetadata().getLabels(), hasEntry(key, value)));
        assertThat(version.getMetadata().getOwnerReferences().size(), is(1));
        assertThat(version.getMetadata().getOwnerReferences().get(0).getName(), is(decision.getMetadata().getName()));
        assertThat(version.getSpec(), is(decision.getSpec().getDefinition()));
        assertThat(version.getStatus().isReady(), is("True"));
        Mockito.verify(remoteResourceClient, Mockito.times(1)).notify(decision, version.getMetadata().getName(), null, Phase.CURRENT);
    }


    @Test
    void testRollbackVersion() throws IOException {
        //Given
        Decision decision = new DecisionBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("some-decision")
                        .withNamespace(CUSTOMER_NS)
                        .addToLabels(CUSTOMER_LABEL, CUSTOMER)
                        .build())
                .withSpec(new DecisionSpec()
                        .setDefinition(new DecisionVersionSpec()
                                .setSource(URI.create("somesource"))
                                .setVersion("1")))
                .withStatus(new DecisionStatus()
                        .setVersionId("2"))
                .build();
        DecisionVersion previous = new DecisionVersionBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("some-decision-1")
                        .withNamespace(CUSTOMER_NS)
                        .withOwnerReferences(decision.getOwnerReference())
                        .addToLabels(DECISION_LABEL, decision.getMetadata().getName())
                        .addToLabels(CUSTOMER_LABEL, decision.getMetadata().getLabels().get(CUSTOMER_LABEL))
                        .addToLabels(MANAGED_BY_LABEL, OPERATOR_NAME)
                        .build())
                .withStatus(new DecisionVersionStatus()
                        .setReady(Boolean.TRUE)
                        .setImageRef("some-decision-image:latest")
                        .setKogitoServiceRef(decision.getMetadata().getName()))
                .withSpec(decision.getSpec().getDefinition())
                .build();
        client.namespaces().create(new NamespaceBuilder().withMetadata(new ObjectMetaBuilder().withName(CUSTOMER_NS).build()).build());
        client.customResource(KOGITO_RUNTIME_CONTEXT).create(CUSTOMER_NS, getKogitoRuntime(previous));
        client.customResources(DecisionVersion.class).inNamespace(CUSTOMER_NS).create(previous);

        //When
        UpdateControl<Decision> updateControl = decisionController.createOrUpdateResource(decision, null);

        //Then
        assertThat(updateControl.isUpdateStatusSubResource(), is(true));
        DecisionVersion version = client.customResources(DecisionVersion.class).inNamespace(CUSTOMER_NS).withName("some-decision-1").get();
        assertThat(version.getMetadata().getName(), is("some-decision-1"));
        Map<String, String> expectedLabels = Map.of(DECISION_LABEL, decision.getMetadata().getName(), MANAGED_BY_LABEL, OPERATOR_NAME, CUSTOMER_LABEL, CUSTOMER);
        expectedLabels.forEach((key, value) -> assertThat(version.getMetadata().getLabels(), hasEntry(key, value)));
        assertThat(version.getMetadata().getOwnerReferences().size(), is(1));
        assertThat(version.getMetadata().getOwnerReferences().get(0).getName(), is(decision.getMetadata().getName()));
        assertThat(version.getSpec(), is(decision.getSpec().getDefinition()));
        assertThat(version.getStatus().isReady(), is("True"));
        Mockito.verify(remoteResourceClient, Mockito.times(1)).notify(decision, version.getMetadata().getName(), null, Phase.CURRENT);
    }

    private String getKogitoRuntime(DecisionVersion previous) {
        JsonObject kogitoRuntime = KogitoService.buildService(previous);
        Condition deployedCondition = new ConditionBuilder().withType("Deployed")
                .withStatus(ResourceUtils.capitalize(Boolean.TRUE))
                .build();

        return Json.createObjectBuilder()
                .add("apiVersion", KOGITO_RUNTIME_CONTEXT.getGroup() + "/" + KOGITO_RUNTIME_CONTEXT.getVersion())
                .add("kind", KOGITO_RUNTIME_CONTEXT.getKind())
                .add("metadata", kogitoRuntime.get("metadata"))
                .add("spec", kogitoRuntime.get("spec"))
                .add("status", Json.createObjectBuilder()
                        .add("externalURI", "decision.kogito.svc")
                        .add("conditions", Json.createArrayBuilder()
                                .add(JsonResourceUtils.toJson(deployedCondition))
                                .build()))
                .build()
                .toString();
    }
}
