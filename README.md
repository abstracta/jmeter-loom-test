# jmeter-loom-test

This project uses [JMeter DSL](https://abstracta.github.io/jmeter-java-dsl) to implement a new JMeter thread group using OpenJDK project Loom virtual threads and compare them to default JMeter thread group implementation.

Check the post [here](https://abstracta.us/blog/performance-testing/virtual-threads-jmeter-meets-project-loom/) for more details.

## Pre requisites

* Java 19+
* Maven 3.5+

## Usage

Generate the jar for th project:

```mvn
mvn clean package
```

And run it with the setup you prefer like:

```bash
JAR_PATH="target/" THREAD_COUNTS="100 500" URL="http://mysite" ./run.sh
```

You may also spin some Amazon EC2 instances and run the tests on them. Check [pulumi directory](pulumi/README.md) for more details.
