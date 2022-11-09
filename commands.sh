#!/bin/bash

## Run Simple Vector Addition
tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.HelloTornado 


## Running Mandelbrot 
tornado --threadInfo --jvm="-Ds0.t0.device=0:0" -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot --params="--tornado"


## Run Blur Filter 
tornado --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter --params="--tornado"

