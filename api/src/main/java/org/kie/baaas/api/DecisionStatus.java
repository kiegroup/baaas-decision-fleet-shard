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

package org.kie.baaas.api;

import java.net.URI;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "endpoint",
        "revisionId",
        "revisionName"
})
@RegisterForReflection
@Buildable(editableEnabled = false, generateBuilderPackage = true, lazyCollectionInitEnabled = false, builderPackage = "io.fabric8.kubernetes.api.builder")
@ToString
@EqualsAndHashCode
public class DecisionStatus {

    private URI endpoint;

    private Long revisionId;

    private String revisionName;

    public URI getEndpoint() {
        return endpoint;
    }

    public DecisionStatus setEndpoint(URI endpoint) {
        this.endpoint = endpoint;
        return this;
    }

    public Long getRevisionId() {
        return revisionId;
    }

    public DecisionStatus setRevisionId(Long revisionId) {
        this.revisionId = revisionId;
        return this;
    }

    public String getRevisionName() {
        return revisionName;
    }

    public DecisionStatus setRevisionName(String revisionName) {
        this.revisionName = revisionName;
        return this;
    }

}
