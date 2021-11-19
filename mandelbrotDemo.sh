

## List all devices and backends available 
tornado --devices 

## Run the multi-thread version for reference 
tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot --mt

## Run the TornadoVM Version (it will select the device 0:0 by default)
tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot --tornado

## Print the device and thread information in which the application is running
tornado --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot --tornado

## Get the SPIRV code (assuming the SPIRV backend is installed in the device 0:0)
tornado --debug --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot --tornado
spirv-dis <spirv-binary> 

## Change the device
tornado --threadInfo -Ds0.t0.device=1:1 -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot --tornado

## Run with the tornadoVM profiler
tornado --enableProfiler console --threadInfo -Ds0.t0.device=0:0 -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot --tornado

