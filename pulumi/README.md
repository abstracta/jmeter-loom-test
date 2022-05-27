# pulumi

This is a [pulumi](https://www.pulumi.com/) project for AWS infrastructure to run tests.

## Pre-requisites

* pulumi 3+

## Setup

* Create a new pulumi stack. Eg:
  ```bash
  pulumi stack init jmeter-loom-dev
  ```
* Configure AWS availability zone. Eg:
  ```bash
  pulumi config set aws:region us-east-1
  ```
* Generate an ssh key. Eg: 
  ```bash
  ssh-keygen -t rsa -f ~/.ssh/jmeter-loom_rsa
  ```
* Configure pulumi key `sshKey` with generated key. Eg:
  ```bash
  pulumi config set --secret sshKey "$(cat ~/.ssh/jmeter-loom_rsa.pub)"
  ```

## Usage

* Run `pulumi up` to spin up the infrastructure.
* Upload test jar. Eg:
  ```bash
  scp -i ~/.ssh/jmeter-loom_rsa ../target/jmeter-loom-test.jar ec2-user@$(pulumi stack output jmeterPublicIp):/home/ec2-user/
  ```
* Upload test script. Eg:
  ```bash
  scp -i ~/.ssh/jmeter-loom_rsa ../run.sh ec2-user@$(pulumi stack output jmeterPublicIp):/home/ec2-user/
  ```
* Execute test script: Eg: 
  ```bash
  ssh -i ~/.ssh/jmeter-loom_rsa ec2-user@$(pulumi stack output jmeterPublicIp) 'JVM_OPTS="-Xmx3G" JAR_PATH="./" THREAD_COUNTS="1000 5000" URL="http://'$(pulumi stack output nginxPrivateIp)'" ./run.sh'
  ```

