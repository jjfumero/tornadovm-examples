## TornadoVM Examples

[TornadoVM](https://github.com/beehive-lab/TornadoVM) is a Java library for hardware acceleration of Java and JVM applications. 
It has a JIT compiler, a runtime system, and several backends that offload, manage memory and handle execution on GPUs, FPGAs, and multicore CPUs transparently. 

This repository contains a few examples for demonstration purposes. 

Note: Examples using TornadoVM [v1.0.4-dev](https://github.com/beehive-lab/TornadoVM/tree/develop)


Outline:

| Topic                       | Link                                                                                                            |
|:----------------------------|:---------------------------------------------------------------------------------------------------------------:|
| Install TornadoVM           | [link](https://github.com/jjfumero/tornadovm-examples?tab=readme-ov-file#1-build-tornadovm)                     |
| Setup the examples          | [link](https://github.com/jjfumero/tornadovm-examples?tab=readme-ov-file#2-setup-the-examples)                  |
| Mandelbrot demo             | [link](https://github.com/jjfumero/tornadovm-examples?tab=readme-ov-file#mandelbrot)                            |
| Blur Filter demo            | [link](https://github.com/jjfumero/tornadovm-examples?tab=readme-ov-file#blur-filter)                           |
| Multi-Image Processing demo | [link](https://github.com/jjfumero/tornadovm-examples?tab=readme-ov-file#multi-image-processing)                |
| KMeans Clustering demo      | [link](https://github.com/jjfumero/tornadovm-examples?tab=readme-ov-file#kmeans-clustering)                     |
| Live Task Migration demo    | [link](https://github.com/jjfumero/tornadovm-examples?tab=readme-ov-file#live-task-migration-client-server-app) |


## 1. Build TornadoVM

To run the examples, first build TornadoVM with any backend (OpenCL, PTX and/or SPIR-V).

**Important:** If you do not have an NVIDIA GPU and the CUDA SDK installed, do not use the flag `--ptx` in the following command. 
Similarly, if your device/system does not support [SPIR-V](https://www.khronos.org/spir/), do not use the `--spirv` flag. 

To install TornadoVM, it requires as prerequisite:
1. The driver installed (e.g., NVIDIA + CUDA Driver for NVIDIA GPUs, or oneAPI for Intel platforms). 
2. [Maven](https://maven.apache.org/download.cgi?Preferred=ftp://ftp.osuosl.org/pub/apache/)

TornadoVM includes an easy installer script for Linux and OSx: 

```bash
$ git clone https://github.com/beehive-lab/TornadoVM
$ cd TornadoVM
## Choose the backend/s that applies to your system. You can install multiple ones
$ ./bin/tornadovm-installer --jdk jdk21 --backend=opencl,ptx,spirv  
$ source setvars.sh
```

### Check installation:

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
tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot mt

## Run the TornadoVM Version (it will select the device 0:0 by default)
tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot tornado

## Print the device and thread information in which the application is running
tornado --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot tornado

## Get the SPIRV code (assuming the SPIRV backend is installed in the device 0:0)
tornado --debug --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot tornado
spirv-dis <spirv-binary> 

## Change the device
tornado --threadInfo --jvm="-Ds0.t0.device=1:1" -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot tornado

## Run with the tornadoVM profiler
tornado --enableProfiler console --threadInfo --jvm="-Ds0.t0.device=0:0" -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.Mandelbrot tornado
```


### Blur Filter

This examples shows a blur effect in a photo. Example of computational photography. 
Place an JPEG image in `./image.jpg` or feel free to change the path your images. 


```bash
## List all devices and backends available 
tornado --devices 

## Run the Java Parallel Stream Version on CPU for reference 
tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter mt

## Run the Accelerated Version on the default device 
tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter tornado

## Run the Accelerated Version on the default device with info about the accelerator 
tornado --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter tornado

## Print the generated kernel
tornado --printKernel --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter tornado

## Run in another device
tornado --threadInfo -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter tornado device=1:1

## Run with concurrent multi-devices
tornado --threadInfo --enableConcurrentDevices --jvm=" -Dblur.red.device=1:0 -Dblur.green.device=2:0 -Dblur.blue.device=1:2" -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.BlurFilter tornado
```

### Multi-Image Processing

Demonstration of a Task-Graph to compute:
  - Black and White filter
  - Blur Filer 

using multiple GPUs (or accelerators) at the same time for each task.

```bash
## Run the Java Parallel Stream Version on CPU for reference 
tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.MultiImageProcessor mt

## Run the Accelerated Version on the default device 
tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.MultiImageProcessor tornado
```

The examples is created based on the following device setup:


```bash
## List all devices and backends available 
$ tornado --devices 

Number of Tornado drivers: 3
Driver: SPIRV
  Total number of SPIRV devices  : 1
  Tornado device=0:0  (DEFAULT)
	SPIRV -- SPIRV LevelZero - Intel(R) UHD Graphics 770
		Global Memory Size: 24.9 GB
		Local Memory Size: 64.0 KB
		Workgroup Dimensions: 3
		Total Number of Block Threads: [512]
		Max WorkGroup Configuration: [512, 512, 512]
		Device OpenCL C version:  (LEVEL ZERO) 1.3

Driver: OpenCL
  Total number of OpenCL devices  : 4
  Tornado device=1:0
	OPENCL --  [NVIDIA CUDA] -- NVIDIA GeForce RTX 3070
		Global Memory Size: 7.8 GB
		Local Memory Size: 48.0 KB
		Workgroup Dimensions: 3
		Total Number of Block Threads: [1024]
		Max WorkGroup Configuration: [1024, 1024, 64]
		Device OpenCL C version: OpenCL C 1.2

  Tornado device=1:1
	OPENCL --  [Intel(R) OpenCL Graphics] -- Intel(R) UHD Graphics 770
		Global Memory Size: 24.9 GB
		Local Memory Size: 64.0 KB
		Workgroup Dimensions: 3
		Total Number of Block Threads: [512]
		Max WorkGroup Configuration: [512, 512, 512]
		Device OpenCL C version: OpenCL C 1.2

  Tornado device=1:2
	OPENCL --  [Intel(R) OpenCL] -- 12th Gen Intel(R) Core(TM) i7-12700K
		Global Memory Size: 31.1 GB
		Local Memory Size: 32.0 KB
		Workgroup Dimensions: 3
		Total Number of Block Threads: [8192]
		Max WorkGroup Configuration: [8192, 8192, 8192]
		Device OpenCL C version: OpenCL C 3.0

  Tornado device=1:3
	OPENCL --  [Intel(R) FPGA Emulation Platform for OpenCL(TM)] -- Intel(R) FPGA Emulation Device
		Global Memory Size: 31.1 GB
		Local Memory Size: 256.0 KB
		Workgroup Dimensions: 3
		Total Number of Block Threads: [67108864]
		Max WorkGroup Configuration: [67108864, 67108864, 67108864]
		Device OpenCL C version: OpenCL C 1.2

Driver: PTX
  Total number of PTX devices  : 1
  Tornado device=2:0
	PTX -- PTX -- NVIDIA GeForce RTX 3070
		Global Memory Size: 7.8 GB
		Local Memory Size: 48.0 KB
		Workgroup Dimensions: 3
		Total Number of Block Threads: [2147483647, 65535, 65535]
		Max WorkGroup Configuration: [1024, 1024, 64]
		Device OpenCL C version: N/A
```

To change the accelerator, use the following instructions:


```java
TornadoDevice device0 = TornadoExecutionPlan.getDevice(0, 0);
TornadoDevice device1 = TornadoExecutionPlan.getDevice(1, 0);
TornadoDevice device2 = TornadoExecutionPlan.getDevice(1, 1);
TornadoDevice device3 = TornadoExecutionPlan.getDevice(1, 2);
TornadoDevice device4 = TornadoExecutionPlan.getDevice(2, 0);

executionPlan.withConcurrentDevices() //
	.withDevice("imageProcessor.blackAndWhite", device0) //
	.withDevice("imageProcessor.blurRed", device1) //
	.withDevice("imageProcessor.blurGreen", device2) //
	.withDevice("imageProcessor.blurBlue", device4);
```


### KMeans Clustering

Full KMeans in which the assign-cluster function is expressed with TornadoVM. 

```bash
# For example use 1000000 data points and classify them into 10 clusters.
# Points are selected randomly. This is just for quick experiments 

## Sequential
tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.KMeans seq 1000000 10


# TornadoVM version 
tornado -cp target/tornadovm-examples-1.0-SNAPSHOT.jar io.github.jjfumero.KMeans tornado 1000000 10
```


### Live Task Migration (Client-Server App)


```bash
## Run Server in one terminal
./runServer.sh

## Client in another terminal
./runClient.sh  ## Change device during runtime 

## Note: the application selects the backend 0:0 (default backend)

# type different <backend:deviceNumber> version from the client. 
# Examples:
# 0:1 
# 1:0 
## etc
```
