#!/bin/bash

tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.HelloTornado 


## Running Mandelbrot 
tornado --threadInfo -Ds0.t0.device=0:0 -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot --tornado
