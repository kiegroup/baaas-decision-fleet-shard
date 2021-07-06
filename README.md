# Decision Fleet Shard

## Requirements

* Apache Maven
* Java version: GraalVM 11
* Podman/Docker for container builds
* Testing

## Build

### JVM Build

```shell script
$ mvn clean install
```

### Native Build

```shell script
$ mvn clean install -Pnative -Dquarkus.native.container-build=true
```

### Create container from the native build

```shell script
podman build -t quay.io/ruben/baaas-decision-fleet-shard:latest \
 -f decision-fleet-shard-operator/src/main/docker/Dockerfile.native \
 decision-fleet-shard-operator
```

## Run on CodeReady Containers

### Install OLM

Follow the steps to install your preferred version of the [Operator Lifecycle Manager](https://github.com/operator-framework/operator-lifecycle-manager/blob/v0.18.0/doc/install/install.md)
The previous command will create a Minikube instance. Make sure the default values in your profile are big enough for
the load you expect.

```shell script
make run-local
```

#### Start the console

```shell script
./scripts/run_console_local.sh
```

## Run on CodeReady Containers

Make sure you have your [CodeReady Containers](https://cloud.redhat.com/openshift/create/local) instance running locally.

First log in as an administrator and create the project in which the operator will run in. 
This is where we are going to create the DecisionRequests.

```shell script
$ oc new-project baaas-dfs
```

## Install the dependencies

From the OLM Console install:

* OpenShift Pipelines Operator 1.4.1
* Kogito Operator 1.5.0

## Install the BAaaS resources

There is a convenience set of scripts that helps you provision all the necessary resources for the pipeline.

Required files for running the script:

### user-credentials.sh

Executable file that sets environment variables used to fetch the decision resources from AWS. Example:

```shell script
#!/usr/bin/env bash

AWS_ACCESS_KEY_ID=<the-id>
AWS_DEFAULT_REGION=eu-west-1
AWS_SECRET_ACCESS_KEY=<the-key>

KAFKA_CLIENT_ID=srvc-acct-....
KAFKA_CLIENT_SECRET=<kafka-client-secret>
KAFKA_SERVER=<kafka-bootstrapserver>

```

### user-pull-secret.yaml

**Only for Minikube**: Required to pull images from registry.redhat.io. Will be created as is and added to the default service account. Example:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: my-pull-secret
data:
  .dockerconfigjson: <docker config json contents>
type: kubernetes.io/dockerconfigjson
```

### Deploy resources

Execute the [tests/deploy-resources.sh](./tests/deploy-resources.sh) file that will create a `target` folder where will 
clone the `baaas-infra` repository. Then it will create the following resources on the current namespace you're using.

* Create crds
* Create all the ConfigMaps used by the pipeline
* Create the Pipeline and Task
* Create the PVC to use as Maven Cache
* Create secrets for AWS and registry pull (if needed)
* Patch the default service account to add the imagePullSecret

### Undeploy resources

Execute the [tests/undeploy-resources.sh](./tests/undeploy-resources.sh) to remove all the provisioned scripts.
If you also want to remove the `target` folder, set `clean` as an argument.

## Quarkus Dev mode

To attach your favourite IDE to the application debug port use the default `5005` port.

```shell script
$ mvn clean compile quarkus:dev
...
Listening for transport dt_socket at address: 5005
...
```

Note that you must be logged in to a Kubernetes cluster. It can be the case that the cluster uses an untrusted certificate
e.g. self-signed. In that case, make sure the JDK trusts the certificate. That is not usually the case in Minikube.

First extract the certificate from the Kubernetes cluster and store it in a file.

```shell script
$ openssl s_client -showcerts -connect api.mylab.example.com:6443
Certificate chain
 0 s:CN = api.mylab.example.com
   i:OU = openshift, CN = kube-apiserver-lb-signer
-----BEGIN CERTIFICATE-----
...
-----END CERTIFICATE-----
```

Then add it to your JDK Cacerts

```shell script
keytool -importcert -keystore ${GRAALVM_HOME}/lib/security/cacerts -storepass changeit -file ./labcert.crt -alias "mylab-cert"
```

# Continuous Delivery (CD)

Decision Fleet Shard is migrating to a model of continuous delivery in that any change merged to `main` branch
will be automatically proposed as a change to our demo environment. 

## Controlling the Changes that Trigger the CD Pipeline

**NOTE: This is temporarily disabled pending our migration to GitHub**

It is possible to control which resources trigger the full continuous delivery pipeline. It is not
always desirable for the pipeline to run if only certain files have been changed e.g. the change only
impacts `README.md`.

To add a file to the list that causes the CD pipeline to execute, you need to ensure it is contained
in the `changes` directive within the [.cd-allowlist.yml](.cd-allowlist.yml).

So for example, to add the file `foo.txt` to the list of files that will trigger the CD pipeline, ensure
you have the following in `.cd-allowlist.yml`:

```yaml
.cd-allowlist:
  only:
    changes:
      - api/src/main/**
      - api/pom.xml
      - decision-fleet-shard-operator/src/main/**
      - decision-fleet-shard-operator/pom.xml
      - pom.xml
      - foo.txt     
```
