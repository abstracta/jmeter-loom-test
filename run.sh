#!/bin/bash
# This script eases execution of multiple test runs
# here are some invocation examples
# JAR_PATH="target/" THREAD_COUNTS="100 500 1000 5000" URL="http://opencart.abstracta.us" ./run.sh
# JVM_OPTS="-Xmx3G" JAR_PATH="./" THREAD_COUNTS="1000 5000" URL="http://$1" ./run.sh
# JAR_PATH="target/" THREAD_COUNTS="1000" IMPLS="default" ./run.sh
# JAR_PATH="target/" THREAD_COUNTS="1000 5000 10000 100000" IMPLS="virtual" ./run.sh

set -ex

JAR_PATH=${JAR_PATH:-"target/"}
THREAD_COUNTS=${THREAD_COUNTS:-"1"}
IMPLS=${IMPLS:-"default virtual"}
TEST_DURATION_SECONDS=${TEST_DURATION_SECONDS:-300}
ITERATIONS=${ITERATIONS:-3}
SLEEP=${SLEEP:-10}
trap 'kill $(jobs -p)' SIGINT

for THREAD_COUNT in $THREAD_COUNTS; do
	for IMPL in $IMPLS; do
		for i in $(seq 1 $ITERATIONS); do
		  [[ "$URL" == "" ]] && URL_PARAM="" || URL_PARAM="-u $URL"
			java $JVM_OPTS -jar --enable-preview ${JAR_PATH}jmeter-loom-test.jar ${URL_PARAM} -d ${TEST_DURATION_SECONDS} -t ${THREAD_COUNT} -impl ${IMPL} &
			TEST_PID=$!
			if [[ "$OSTYPE" == "darwin"* ]]; then
			  top -pid $TEST_PID -stats cpu,mem | grep --line-buffered -e "^\\d*\\.\\d*" > ${IMPL}-${THREAD_COUNT}-${i}-top.out &
			else
			  top -p $TEST_PID -b | grep -e "^\\s\\+[0-9]" > ${IMPL}-${THREAD_COUNT}-${i}-top.out &
			fi
			TOP_PID=$!
			wait $TEST_PID
			kill $TOP_PID
			wait $TOP_PID 2>/dev/null || true
			sleep $SLEEP
    done
  done
done
