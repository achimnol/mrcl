# Mr.CL Project #

## Overview ##
We combine the power of two major tools for data processing: Hadoop and NVIDIA CUDA. Hadoop is for scalability over multiple nodes and CUDA is for speeding up block-level calculations.

The goal of this project was improving distributed matrix multiplication on Hadoop. Hadoop emphasizes data locality using HDFS, but matrix multiplication has to access remote data unless columns or rows are aligned to each machine. This makes matrix multiplication infeasible on MapReduce scheme.

Our approach is block-based matrix multiplication. Blocks in a fixed size are stored on HDFS, and local matrix multiplications are performed using CuBLAS library. Our experiment showed this can improve the overall calculation performance for certain configurations. For details, see [our report](http://mrcl.googlecode.com/svn/trunk/report/report.pdf) (in Korean). We're welcome your feedback or benchmarks on other configurations.

Further study and development is required, especially on data organization issues because our method is actually not very scalable due to accessing remote blocks.

## Acknowledgement ##
This project is done as a term project of _CS492 Special Topics in Computer Science: Distributed Algorithms and Systems_ (instructor: [Sue Moon](http://an.kaist.ac.kr/~sbmoon/)), a course in KAIST CS Department.

We also referred [Apache Hama project](http://incubator.apache.org/hama/), which is also an implementation of matrix multiplication with some other operations on Hadoop + HBase. At first, we tried to extend Hama itself, but made a new implementation to avoid using HBase because its performance was the main bottleneck compared to speed-ups on calculation.