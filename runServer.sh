#!/bin/bash

echo "tornado --printKernel --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.taskmigration.Server "
tornado --threadInfo --printKernel -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.taskmigration.Server 


