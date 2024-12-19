# #################################################################################################
# Simple program to calculate the Floating point operations per second for the Matrix Multiplication
# #################################################################################################

import argparse

def parseArguments():
    parser = argparse.ArgumentParser(prog='compute_flops_mxm', 
                description='Compute the Floating Point Operations per Second for the matrix multiplication given the time and the input matrix size')
    parser.add_argument('--size', dest="size", type=float, default=None, help="Set the input matrix size (size x size)")
    parser.add_argument('--time', dest="time", type=float, default=None, help="Set the time in nanoseconds (ns)")
    return parser.parse_args()

def computeFlops(args):
    matrixSize = args.size 
    nanosecondsSolution = args.time
    numFloatOperationsPerKernel = 2 * matrixSize**3 
    timeScaleSec = 1E9
    gigaflops = (1E-9 * numFloatOperationsPerKernel) / (nanosecondsSolution / timeScaleSec)
    print("GigaGlops: " + str(round(gigaflops, 2)))

if __name__ == "__main__":
    args = parseArguments()
    computeFlops(args)
