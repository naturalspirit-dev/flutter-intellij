#!/bin/bash

# Fail on any error.
#set -e

# Display commands being run. Only do this while debugging and be careful
# that no confidential information is displayed.
# set -x

java -version
echo "JAVA_HOME=$JAVA_HOME"
ant -version
curl --version
zip --version

echo "kokoro build finished"
