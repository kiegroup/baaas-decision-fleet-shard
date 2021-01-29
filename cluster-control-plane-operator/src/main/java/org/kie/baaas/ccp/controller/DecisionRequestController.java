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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.utils.KubernetesResourceUtil;
import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.api.Controller;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import org.kie.baaas.api.AdmissionStatus;
import org.kie.baaas.api.Decision;
import org.kie.baaas.api.DecisionBuilder;
import org.kie.baaas.api.DecisionConstants;
import org.kie.baaas.api.DecisionRequest;
import org.kie.baaas.api.DecisionRequestSpec;
import org.kie.baaas.api.DecisionRequestStatusBuilder;
import org.kie.baaas.api.DecisionSpecBuilder;
import org.kie.baaas.api.DecisionStatus;
import org.kie.baaas.api.DecisionVersion;
import org.kie.baaas.api.DecisionVersionRef;
import org.kie.baaas.api.Phase;
import org.kie.baaas.ccp.controller.model.DecisionValidationException;
import org.kie.baaas.ccp.service.DecisionVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.kie.baaas.ccp.controller.DecisionController.DECISION_LABEL;

@Controller
@ApplicationScoped
public class DecisionRequestController implements ResourceController<DecisionRequest> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionRequestController.class);
    private static final String BAAAS_NS_TEMPLATE = "baaas-%s";
    public static final String DECISION_REQUEST_LABEL = "org.kie.baaas.decisionrequest";
    public static final String CUSTOMER_LABEL = "org.kie.baaas.customer";

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    Validator validator;

    @Inject
    DecisionVersionService decisionVersionService;

    public DeleteControl deleteResource(DecisionRequest request, Context<DecisionRequest> context) {
        LOGGER.info("Delete DecisionRequest: {} in namespace {}", request.getMetadata().getName(), request.getMetadata().getNamespace());
        return DeleteControl.DEFAULT_DELETE;
    }

    public UpdateControl<DecisionRequest> createOrUpdateResource(DecisionRequest request, Context<DecisionRequest> context) {
        LOGGER.info("Create or update DecisionRequest: {} in namespace {}", request.getMetadata().getName(), request.getMetadata().getNamespace());
        String targetNamespace = getTargetNamespace(request);
        try {
            validateSpec(request.getSpec(), targetNamespace);
        } catch (DecisionValidationException e) {
            request.setStatus(new DecisionRequestStatusBuilder()
                    .withReason(e.getReason())
                    .withMessage(e.getMessage())
                    .withState(AdmissionStatus.REJECTED)
                    .build());
            return UpdateControl.updateStatusSubResource(request);
        }
        try {
            Decision decision = createOrUpdateDecision(request, targetNamespace);
            return updateSuccessRequestStatus(request, decision);
        } catch (KubernetesClientException e) {
            request.setStatus(new DecisionRequestStatusBuilder()
                    .withReason(DecisionConstants.SERVER_ERROR)
                    .withMessage(e.getMessage())
                    .withState(AdmissionStatus.REJECTED)
                    .build());
            return UpdateControl.updateStatusSubResource(request);
        }
    }

    //TODO: Fix - Validator cannot discover the annotated classes
    private void validateSpec(DecisionRequestSpec spec, String namespace) throws DecisionValidationException {
        //TODO: Remove once validator is fixed
        if (spec.getCustomerId() == null || spec.getCustomerId().isBlank()) {
            throw new DecisionValidationException(DecisionConstants.VALIDATION_ERROR, "Invalid spec: customerId must not be blank");
        }
        // End
        Set<ConstraintViolation<DecisionRequestSpec>> violations = validator.validate(spec);
        if (!violations.isEmpty()) {
            throw new DecisionValidationException(DecisionConstants.VALIDATION_ERROR, "Invalid spec: " +
                    violations.stream()
                            .map(v -> v.getPropertyPath() + " " + v.getMessage())
                            .collect(Collectors.joining(",")));
        }
        if (!KubernetesResourceUtil.isValidName(namespace)) {
            throw new DecisionValidationException(DecisionConstants.VALIDATION_ERROR, "Invalid target namespace: " + namespace);
        }
        validateVersion(spec, namespace);
    }

    private void validateVersion(DecisionRequestSpec spec, String namespace) throws DecisionValidationException {
        List<DecisionVersion> versions = kubernetesClient.customResources(DecisionVersion.class)
                .inNamespace(namespace)
                .withLabel(DECISION_LABEL, spec.getName())
                .list()
                .getItems().stream().filter(v -> v.getSpec().getVersion().equals(spec.getDefinition().getVersion())).collect(Collectors.toList());
        for (DecisionVersion v : versions) {
            if (Phase.FAILED.equals(v.getStatus().isBuildFailed())) {
                throw new DecisionValidationException(DecisionConstants.VERSION_BUILD_FAILED, "Requested DecisionVersion build failed");
            }
            if (!v.getSpec().equals(spec.getDefinition())) {
                throw new DecisionValidationException(DecisionConstants.DUPLICATED_VERSION, "The provided version already exists with a different spec");
            }
        }
    }

    private String getTargetNamespace(DecisionRequest decision) {
        return String.format(BAAAS_NS_TEMPLATE, decision.getSpec().getCustomerId());
    }

    private Decision createOrUpdateDecision(DecisionRequest request, String namespace) {
        Namespace targetNs = kubernetesClient.namespaces().withName(namespace).get();
        if (targetNs == null) {
            kubernetesClient.namespaces()
                    .create(new NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build());
        }
        Decision expected = new DecisionBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(request.getSpec().getName())
                        .withNamespace(namespace)
                        .addToLabels(DECISION_REQUEST_LABEL, request.getMetadata().getUid())
                        .addToLabels(CUSTOMER_LABEL, request.getSpec().getCustomerId())
                        .build())
                .withSpec(new DecisionSpecBuilder()
                        .withDefinition(request.getSpec().getDefinition())
                        .withWebhooks(request.getSpec().getWebhooks())
                        .build())
                .withStatus(new DecisionStatus())
                .build();
        Decision current = kubernetesClient.customResources(Decision.class).inNamespace(namespace).withName(request.getMetadata().getName()).get();
        if (current == null || !expected.getSpec().equals(current.getSpec())) {
            current = kubernetesClient.customResources(Decision.class)
                    .inNamespace(namespace)
                    .withName(expected.getMetadata().getName())
                    .createOrReplace(expected);
        }
        return current;
    }

    private UpdateControl<DecisionRequest> updateSuccessRequestStatus(DecisionRequest request, Decision decision) {
        if (request.getStatus() == null || (AdmissionStatus.SUCCESS.equals(request.getStatus().getState()) &&
                decision.getMetadata().getName().equals(request.getStatus().getVersionRef().getName()) &&
                decision.getMetadata().getNamespace().equals(request.getStatus().getVersionRef().getNamespace()))) {
            request.setStatus(new DecisionRequestStatusBuilder()
                    .withState(AdmissionStatus.SUCCESS)
                    .withVersionRef(new DecisionVersionRef()
                            .setName(decision.getMetadata().getName())
                            .setNamespace(decision.getMetadata().getNamespace())
                            .setVersion(request.getSpec().getDefinition().getVersion()))
                    .withMessage(null)
                    .withReason(null)
                    .build());
            return UpdateControl.updateStatusSubResource(request);
        }
        return UpdateControl.noUpdate();
    }

}
