#!/usr/bin/env bash

# Copyright © 2014 Cask Data, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"
bin=`dirname "${BASH_SOURCE-$0}"`
bin=`cd "$bin"; pwd`
lib="$bin"/../lib
conf="$bin"/../conf
# Add conf directory
CDAP_CONF=${CDAP_CONF:-/etc/cdap/conf}
script=`basename $0`

# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done
SAVED="`pwd`"
cd "`dirname \"$PRG\"`/.."
APP_HOME="`pwd -P`"

# In other environment, the jars are expected to be in <HOME>/lib directory.
# Load all the jar files. Not ideal, but we need to load only the things that
# is needed by this script.
if [ "$CLASSPATH" = "" ]; then
  CLASSPATH=${lib}/*
else
  CLASSPATH=$CLASSPATH:${lib}/*
fi

# Load the configuration too.
if [ -d "$CDAP_CONF" ]; then
  CLASSPATH=$CLASSPATH:"$CDAP_CONF"
elif [ -d "$conf" ]; then
  CLASSPATH=$CLASSPATH:"$conf"/
fi

auth_file="$HOME/.cdap.accesstoken"
# add token file arg with default token file if one is not provided
containsTokenFileArg=0
for var in "$@"
do
  if [ "$var" = "--token-file" ]; then
    containsTokenFileArg=1
  fi
done

if [ $containsTokenFileArg -eq 0 ] && [ -f $auth_file ]; then
  set -- "$@" "--token-file" "$auth_file"
fi

java -cp ${CLASSPATH} -Dscript=$script co.cask.cdap.data2.transaction.TransactionManagerDebuggerMain "$@"
