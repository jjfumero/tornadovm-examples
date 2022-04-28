## Examples for TornadoVM 

[TornadoVM](https://github.com/beehive-lab/TornadoVM) is a Java library for hardware acceleration of Java and JVM applications. 
It has a JIT compiler, a runtime and several backends to offload, manage memory and run applications on GPUs, FPGAs, and multi-core CPUs in a transparent manner. 

This repository contains a few examples for demonstration purposes. 

Note: Examples using TornadoVM [v0.14-dev](https://github.com/beehive-lab/TornadoVM/tree/develop)

## 1. Build TornadoVM

To run the examples, first build TornadoVM with any backend (OpenCL, PTX and/or SPIR-V).

**Important:** If you do not have an NVIDIA GPU and CUDA installed, do not use the flag `--ptx` in the following command. 
TornadoVM builds with the OpenCL backend by default. 
Similarly, if your device/system does not support [SPIR-V](https://www.khronos.org/spir/), do not use the `--spirv` flag. 

To install TornadoVM, it requires as prerequisite:
1. The driver installed (e.g., NVIDIA + CUDA Driver for NVIDIA GPUs, or oneAPI for Intel platforms). 
2. [Maven](https://maven.apache.org/download.cgi?Preferred=ftp://ftp.osuosl.org/pub/apache/)

TornadoVM includes an easy installer for Linux: 

```bash
$ git clone https://github.com/beehive-lab/TornadoVM
$ cd TornadoVM
$ ./scripts/tornadovmInstaller.sh --jdk17 --opencl --ptx --spirv  ## Choose the ones that applies to your system
$ source source.sh
```

Check installation:

```bash
tornado --devices

Number of Tornado drivers: 2
Total number of PTX devices  : 1
Tornado device=0:0
	PTX -- NVIDIA GeForce RTX 2060 with Max-Q Design
		Global Memory Size: 5.8 GB
		Local Memory Size: 48.0 KB
		Workgroup Dimensions: 3
		Max WorkGroup Configuration: [1024, 1024, 64]
		Device OpenCL C version: N/A

Total number of OpenCL devices  : 3
Tornado device=1:0
	NVIDIA CUDA -- NVIDIA GeForce RTX 2060 with Max-Q Design
		Global Memory Size: 5.8 GB
		Local Memory Size: 48.0 KB
		Workgroup Dimensions: 3
		Max WorkGroup Configuration: [1024, 1024, 64]
		Device OpenCL C version: OpenCL C 1.2

Tornado device=1:1
	Intel(R) OpenCL HD Graphics -- Intel(R) UHD Graphics [0x9bc4]
		Global Memory Size: 24.9 GB
		Local Memory Size: 64.0 KB
		Workgroup Dimensions: 3
		Max WorkGroup Configuration: [256, 256, 256]
		Device OpenCL C version: OpenCL C 3.0

Tornado device=1:2
	Intel(R) CPU Runtime for OpenCL(TM) Applications -- Intel(R) Core(TM) i9-10885H CPU @ 2.40GHz
		Global Memory Size: 31.1 GB
		Local Memory Size: 32.0 KB
		Workgroup Dimensions: 3
		Max WorkGroup Configuration: [8192, 8192, 8192]
		Device OpenCL C version: OpenCL C 2.0
```


Note that, depending on the devices you have and the drivers installed (e.g., NVIDIA CUDA, OpenCL, SPIR-V), you will see different implementations. 



## 2. Setup the examples

```bash
git clone https://github.com/jjfumero/tornadovm-examples
cd tornadovm-examples
source /path-to-your-Tornado-DIR/source.sh
export TORNADO_SDK=/path-to-your-Tornado-DIR/bin/sdk
mvn clean package
```

## Running demos

### Mandelbrot


```bash
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
```


### Blur Filter

This examples shows a blur effect in a photo. Example of computational photography. 
Place an JPEG image in `/tmp/image.jpg` or feel free to change the path your images. 


```bash
## List all devices and backends available 
tornado --devices 

## Run the Accelerated Version on the default device 
tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter --tornado

## Run the Accelerated Version on the default device with info about the accelerator 
tornado --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter --tornado

## Print the generated kernel
tornado --printKernel --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter --tornado

## Run in another device
tornado --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter --tornado --device=1:1 
```


### Live Task Migration (Client-Server App)


```bash
## Run Server in one terminal
./runServer.sh

## Client in another terminal
./runClient.sh  ## Change device during runtime 

## Note: the application selects the backend 0 (default backend)
```
