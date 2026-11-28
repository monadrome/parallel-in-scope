#!/bin/sh
cd /tmp/rso-pit-base || exit 2
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
for t in ThreadPoolStarvationObservationScopeTest ParRuntimeTest; do
  rm -rf core/target/pit-reports
  mvn -o -Ppitest -pl core test-compile org.pitest:pitest-maven:1.30.1:mutationCoverage \
    '-DtargetClasses=io.github.monadrome.parallelinscope.ParRuntime' \
    "-DtargetTests=io.github.monadrome.parallelinscope.$t" \
    '-Dmutators=VOID_METHOD_CALLS' -DoutputFormats=XML \
    -Dpitest.threads=8 -Dpitest.timeoutConstant=60000 -Dpitest.timeoutFactor=1.25 > /tmp/rso-pit-base-$t.log 2>&1
  echo "$t exit=$?"
  cp core/target/pit-reports/mutations.xml /tmp/rso-pit-base-$t.xml 2>/dev/null
done
echo BASE2 DONE
