/*
 * Copyright 2013-2016 Indiana University
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.iu.daal_kmeans.regroupallgather;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;
import java.util.Arrays;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.CollectiveMapper;

import edu.iu.harp.example.DoubleArrPlus;
import edu.iu.harp.partition.Partition;
import edu.iu.harp.partition.Partitioner;
import edu.iu.harp.partition.Table;
import edu.iu.harp.resource.DoubleArray;
import edu.iu.harp.schdynamic.DynamicScheduler;

import edu.iu.daal.*;
import java.nio.DoubleBuffer;

//import daa.jar API
import com.intel.daal.algorithms.kmeans.*;
import com.intel.daal.algorithms.kmeans.init.*;

import com.intel.daal.data_management.data.NumericTable;
import com.intel.daal.data_management.data.HomogenNumericTable;
// import com.intel.daal.data_management.data.HomogenBMNumericTable;
import com.intel.daal.services.DaalContext;
import com.intel.daal.services.Environment;

/**
 * @brief the Harp mapper for running K-means
 */
public class KMeansDaalCollectiveMapper
    extends
    CollectiveMapper<String, String, Object, Object> {

        private int pointsPerFile;
        private int numCentroids;
        private int vectorSize;
        private int numCenPars;
        private int cenVecSize;
        private int numMappers;
        private int numThreads;
        private int numIterations;
        private String cenDir;

        //to measure the time
        private long convert_time = 0;
        private long train_time = 0;
        private long compute_time = 0;
        private long comm_time = 0;

        private static DaalContext daal_Context = new DaalContext();

        /**
         * Mapper configuration.
         */
        @Override
        protected void setup(Context context)
        throws IOException, InterruptedException {
        long startTime = System.currentTimeMillis();
        Configuration configuration =
            context.getConfiguration();
        pointsPerFile =
            configuration.getInt(
                    Constants.POINTS_PER_FILE, 20);
        numCentroids =
            configuration.getInt(
                    Constants.NUM_CENTROIDS, 20);
        vectorSize =
            configuration.getInt(Constants.VECTOR_SIZE,
                    20);
        numMappers =
            configuration.getInt(Constants.NUM_MAPPERS,
                    10);
        // numCenPars = numMappers;
        cenVecSize = vectorSize + 1;
        numThreads =
            configuration.getInt(Constants.NUM_THREADS,
                    10);
        numCenPars = numThreads;
        numIterations =
            configuration.getInt(
                    Constants.NUM_ITERATIONS, 10);
        cenDir = configuration.get(Constants.CEN_DIR);
        LOG.info("Points Per File " + pointsPerFile);
        LOG.info("Num Centroids " + numCentroids);
        LOG.info("Vector Size " + vectorSize);
        LOG.info("Num Mappers " + numMappers);
        LOG.info("Num Threads " + numThreads);
        LOG.info("Num Iterations " + numIterations);
        LOG.info("Cen Dir " + cenDir);
        long endTime = System.currentTimeMillis();
        LOG.info("config (ms) :"
                + (endTime - startTime));
        }

        protected void mapCollective(
                KeyValReader reader, Context context)
            throws IOException, InterruptedException {
            long startTime = System.currentTimeMillis();
            List<String> pointFiles =
                new LinkedList<String>();
            while (reader.nextKeyValue()) {
                String key = reader.getCurrentKey();
                String value = reader.getCurrentValue();
                LOG.info("Key: " + key + ", Value: "
                        + value);
                pointFiles.add(value);
            }
            Configuration conf =
                context.getConfiguration();
            runKmeans(pointFiles, conf, context);
            LOG.info("Total iterations in master view: "
                    + (System.currentTimeMillis() - startTime));
                }

        /**
         * @brief run K-means by invoking DAAL Java API
         *
         * @param fileNames
         * @param conf
         * @param context
         *
         * @return 
         */
        private void runKmeans(List<String> fileNames,
                Configuration conf, Context context)
            throws IOException {

            //pointArrays are used in daal table with feature dimension to be
            //vectorSize instead of cenVecSize
            List<double[]> pointArrays =
                KMUtil.loadPoints(fileNames, pointsPerFile,
                        vectorSize, conf, numThreads);

            //---------------- convert cenTable and pointArrays to Daal table ------------------
            //create the daal table for pointsArrays
            long nFeature = vectorSize;
            long totalLength = 0;

            long[] array_startP = new long[pointArrays.size()];
            double[][] array_data = new double[pointArrays.size()][];

            int totalRecCnt = 0;
            for(int k=0;k<pointArrays.size();k++)
            {
                array_data[k] = pointArrays.get(k);
                array_startP[k] = totalLength;
                totalLength += pointArrays.get(k).length;
                LOG.info("PointArray[" + k + "], len=" + pointArrays.get(k).length + " , reccnt=" +
                        pointArrays.get(k).length*1.0/vectorSize);
                totalRecCnt += pointArrays.get(k).length/vectorSize;
            }
            LOG.info("Total load records number: " + totalRecCnt);


            //random samples to init the centroids
            // Load centroids
            Table<DoubleArray> cenTable =
                new Table<>(0, new DoubleArrPlus());
            if (this.isMaster()) {
                createCenTable(cenTable, numCentroids,
                        numCenPars, cenVecSize);
                LOG.info("Loading Centroids at master, numCenPars=" + numCenPars + ",cenVecSize=" +
                        cenVecSize + ",num=" + numCentroids);
                // random init from sample points
                Random r = new Random();
                double[] cent_data = new double[numCentroids*vectorSize];
                long recid, pos;
                for(int j=0; j < numCentroids; j++){
                    //recid = r.nextLong(totalLength);
                    recid = r.nextInt((int)totalLength/vectorSize);
                    pos = recid * vectorSize;
                    //get the data point of recid
                    
                    for(int k=pointArrays.size()-1;k>=0;k--){
                        if (pos >= array_startP[k]){
                            //found
                            //cent_data[j] = array_data[k][(int)(recid - array_startP[k])];
                            for(int l=0; l < vectorSize; l++){
                                cent_data[j*vectorSize + l] = array_data[k][(int)(pos - array_startP[k]) + l];
                            }

                            break;
                        }
                    }
                    LOG.info("Load Centroid at :" + recid + " , val=" + cent_data[j*vectorSize + 0] + 
                            "," + cent_data[j*vectorSize+1] +"," + cent_data[j*vectorSize+2] +"," + cent_data[j*vectorSize+3]);
                }

                //loadCentroids(cenTable, cenVecSize, cenDir
                //        + File.separator
                //        + Constants.CENTROID_FILE_NAME, conf);
 
                loadCentroidsFromData(cenTable, cenVecSize, cent_data);

            }
            // Bcast centroids
            bcastCentroids(cenTable, this.getMasterID());






            //create daal table for cenTable first so it can be allocated
            //on HBM if available
            long nFeature_cen = vectorSize;
            long totalLength_cen = numCentroids*nFeature_cen;
            long tableSize_cen = totalLength_cen/nFeature_cen;
            // NumericTable cenTable_daal = new HomogenBMNumericTable(daal_Context, Double.class, nFeature_cen, tableSize_cen, NumericTable.AllocationFlag.DoAllocate);
            NumericTable cenTable_daal = new HomogenNumericTable(daal_Context, Double.class, nFeature_cen, tableSize_cen, NumericTable.AllocationFlag.DoAllocate);
            double[] buffer_array_cen = new double[(int)totalLength_cen]; 
            
            long tableSize = totalLength/nFeature;
            // NumericTable pointsArray_daal = new HomogenBMNumericTable(daal_Context, Double.class, nFeature, tableSize, NumericTable.AllocationFlag.DoAllocate);
            NumericTable pointsArray_daal = new HomogenNumericTable(daal_Context, Double.class, nFeature, tableSize, NumericTable.AllocationFlag.DoAllocate);

            int row_idx = 0;
            int row_len = 0;
            for (int k=0; k<pointArrays.size(); k++) 
            {
                row_len = (array_data[k].length)/(int)nFeature;
                //release data from Java side to native side
                // ((HomogenBMNumericTable)pointsArray_daal).releaseBlockOfRowsByte(row_idx, row_len, array_data[k]);
                DoubleBuffer array_data_buf = DoubleBuffer.wrap(array_data[k]);
                pointsArray_daal.releaseBlockOfRows(row_idx, row_len, array_data_buf);
                row_idx += row_len;
            }

            //create the algorithm DistributedStep1Local 
            //to accomplish the first step of computing distances between training points and centroids
            DistributedStep1Local kmeansLocal = new DistributedStep1Local(daal_Context, Double.class, Method.defaultDense, numCentroids);
            kmeansLocal.input.set(InputId.data, pointsArray_daal);
            // kmeansLocal.parameter.setNThreads(numThreads);

            // ----------------------------------------------------- For iterations -----------------------------------------------------
            // set up the maximal threads used by DAAL kernels
            LOG.info("The default value of thread numbers in DAAL: " + Environment.getNumberOfThreads());
            Environment.setNumberOfThreads(numThreads);
            LOG.info("The current value of thread numbers in DAAL: " + Environment.getNumberOfThreads());

            for (int i = 0; i < numIterations; i++) {
                LOG.info("Iteration: " + i);

                long t1 = System.currentTimeMillis();

                //create daal table for cenTable without sentinel element
                //long nFeature_cen = vectorSize;
                //long totalLength_cen = numCentroids*nFeature_cen;

                long[] array_startP_cen = new long[cenTable.getNumPartitions()];
                long[] sentinel_startP_cen = new long[cenTable.getNumPartitions()];
                double[][] array_data_cen = new double[cenTable.getNumPartitions()][];

                int ptr = 0;
                long startP = 0;
                long sentinel_startP = 0;
                for (Partition<DoubleArray> partition : cenTable.getPartitions()) {
                    array_data_cen[ptr] = partition.get().get();
                    array_startP_cen[ptr] = startP;
                    sentinel_startP_cen[ptr] = sentinel_startP;
                    long increment = ((array_data_cen[ptr].length)/(cenVecSize));
                    sentinel_startP += increment;
                    startP += (increment*nFeature_cen);
                    ptr++;
                }

                //Instead of allocate every iteration, allocate once
                //and reuse for each iteration
                //long tableSize_cen = totalLength_cen/nFeature_cen;
                //NumericTable cenTable_daal = new HomogenBMNumericTable(daal_Context, Double.class, nFeature_cen, tableSize_cen, NumericTable.AllocationFlag.DoAllocate);
                //double[] buffer_array_cen = new double[(int)totalLength_cen];
                Arrays.fill(buffer_array_cen, 0);

                //convert training points from List to daal table
                Thread[] threads_cen = new Thread[numThreads];

                //parallel copy partitions of cenTale into an entire primitive array
                for (int q = 0; q<numThreads; q++) 
                {
                    threads_cen[q] = new Thread(new TaskSentinelListToBufferDouble(q, numThreads, (int)nFeature_cen, cenTable.getNumPartitions(), array_startP_cen, array_data_cen, buffer_array_cen));
                    threads_cen[q].start();
                }

                for (int q = 0; q< numThreads; q++) {

                    try
                    {
                        threads_cen[q].join();

                    }catch(InterruptedException e)
                    {
                        System.out.println("Thread interrupted.");
                    }
                }

                //release the array into daal side cenTable
                // ((HomogenBMNumericTable)cenTable_daal).releaseBlockOfRowsByte(0, tableSize_cen, buffer_array_cen);
                DoubleBuffer array_cen_buf = DoubleBuffer.wrap(buffer_array_cen);
                cenTable_daal.releaseBlockOfRows(0, tableSize_cen, array_cen_buf);
                kmeansLocal.input.set(InputId.inputCentroids, cenTable_daal);

                long t2 = System.currentTimeMillis();
                PartialResult pres = kmeansLocal.compute();
                long t3 = System.currentTimeMillis();

                // partialSum: double array vectorSize*numCentroids 
                double[] partialSum = (double[]) ((HomogenNumericTable)pres.get(PartialResultId.partialSums)).getDoubleArray(); 

                // nObservations: int array numCentroids 
                double[] nObservations = (double[]) ((HomogenNumericTable)pres.get(PartialResultId.nObservations)).getDoubleArray();

                //copy partialSum and nObservations back to cenTable
                threads_cen = new Thread[numThreads];
                for (int q = 0; q<numThreads; q++) 
                {
                    threads_cen[q] = new Thread(new TaskSentinelListUpdateDouble(q, numThreads, (int)nFeature_cen, cenTable.getNumPartitions(), array_startP_cen, sentinel_startP_cen, 
                                array_data_cen, partialSum, nObservations));
                    threads_cen[q].start();
                }

                for (int q = 0; q< numThreads; q++) {

                    try
                    {
                        threads_cen[q].join();

                    }catch(InterruptedException e)
                    {
                        System.out.println("Thread interrupted.");
                    }
                }

                //cenTable_daal.freeDataMemory();

                long t4 = System.currentTimeMillis();

                // Allreduce
                regroup("main", "regroup-" + i, cenTable,
                        new Partitioner(this.getNumWorkers()));

                long t5 = System.currentTimeMillis();

                //calculate the average value in multi-threading
                int num_partition = cenTable.getNumPartitions();
                Thread[] threads_avg = new Thread[num_partition];

                int thread_id = 0;
                for (Partition<DoubleArray> partition : cenTable.getPartitions()) 
                {
                    DoubleArray array = partition.get();
                    threads_avg[thread_id] = new Thread(new TaskAvgCalc(cenVecSize, array));
                    threads_avg[thread_id].start();
                    thread_id++;
                }

                for (int q = 0; q< num_partition; q++) {

                    try
                    {
                        threads_avg[q].join();

                    }catch(InterruptedException e)
                    {
                        System.out.println("Thread interrupted.");
                    }
                }

                long t6 = System.currentTimeMillis();

                allgather("main", "allgather-" + i,
                        cenTable);

                long t7 = System.currentTimeMillis();

                // print the sum of centroids, a kind of criteria of stop condition
                printInteria(cenTable,cenVecSize);



                train_time += (t7 - t1);
                compute_time += ((t3 -t2) + (t6 - t5));
                convert_time += ((t2- t1) + (t4 - t3));
                comm_time += ((t5 - t4) + (t7 - t6));

                LOG.info("Compute: " + ((t3 -t2) + (t6 - t5))
                        + ", Convert: " + ((t2- t1) + (t4 - t3))
                        + ", Aggregate: " + ((t5 - t4) + (t7 - t6)));
                logMemUsage();
                logGCTime();
                context.progress();

            }//for iteration


            //After the iteration, free the cenTable
            cenTable_daal.freeDataMemory();

            LOG.info("Time Summary Per Itr: Training: " + train_time/numIterations + 
                    " Compute: " + compute_time/numIterations + 
                    " Convert: " + convert_time/numIterations +
                    " Comm: " + comm_time/numIterations);

            pointsArray_daal.freeDataMemory();
            // calcCompute.stop();
            // mergeCompute.stop();
            // Write out centroids
            if (this.isMaster()) {
                LOG.info("Start to write out centroids.");
                long startTime = System.currentTimeMillis();
                KMUtil.storeCentroids(conf, cenDir,
                        cenTable, cenVecSize, "output");
                long endTime = System.currentTimeMillis();
                LOG.info("Store centroids time (ms): "
                        + (endTime - startTime));
            }
            cenTable.release();
        }

        /**
         * @brief generate the Harp side centroid table
         *
         * @param cenTable
         * @param numCentroids
         * @param numCenPartitions
         * @param cenVecSize
         *
         * @return 
         */
        private void createCenTable(
                Table<DoubleArray> cenTable,
                int numCentroids, int numCenPartitions,
                int cenVecSize) {
            int cenParSize =
                numCentroids / numCenPartitions;
            int cenRest = numCentroids % numCenPartitions;
            int totalSize = 0, size =0;
            for (int i = 0; i < numCenPartitions; i++) {
                if (cenRest > 0) {
                    size = (cenParSize + 1) * cenVecSize;
                    DoubleArray array =
                        DoubleArray.create(size, false);
                    cenTable.addPartition(new Partition<>(i,
                                array));
                    cenRest--;
                } else if (cenParSize > 0) {
                    size = cenParSize * cenVecSize;
                    DoubleArray array =
                        DoubleArray.create(size, false);
                    cenTable.addPartition(new Partition<>(i,
                                array));
                } else {
                    break;
                }
                totalSize += size;
            }

            LOG.info("Create Centroid Table Size: " + totalSize*8*1.0/(1024*1024) + "(MB)");

        }

        /**
         * Fill data from centroid file to cenDataMap
         * 
         * @param cenDataMap
         * @param vectorSize
         * @param cFileName
         * @param configuration
         * @throws IOException
         */
        private void
            loadCentroids(Table<DoubleArray> cenTable,
                    int cenVecSize, String cFileName,
                    Configuration configuration)
            throws IOException {
            long startTime = System.currentTimeMillis();
            Path cPath = new Path(cFileName);
            FileSystem fs = FileSystem.get(configuration);
            FSDataInputStream in = fs.open(cPath);
            BufferedReader br =
                new BufferedReader(
                        new InputStreamReader(in));
            String[] curLine = null;
            int curPos = 0;
            for (Partition<DoubleArray> partition : cenTable
                    .getPartitions()) {
                DoubleArray array = partition.get();
                double[] cData = array.get();
                int start = array.start();
                int size = array.size();
                for (int i = start; i < (start + size); i++) {
                    // Don't set the first element in each row
                    if (i % cenVecSize != 0) {
                        // cData[i] = in.readDouble();
                        if (curLine == null
                                || curPos == curLine.length) {
                            curLine = br.readLine().split(" ");
                            curPos = 0;
                                }
                        cData[i] =
                            Double.parseDouble(curLine[curPos]);
                        curPos++;
                    }
                }
                    }
            br.close();
            long endTime = System.currentTimeMillis();
            LOG.info("Load centroids (ms): "
                    + (endTime - startTime));
            }

        /**
         * Fill data from centroid data(double array)
         * 
         * @param cenDataMap
         * @param vectorSize
         * @param centdata
         */
 
        private void
            loadCentroidsFromData(Table<DoubleArray> cenTable,
                    int cenVecSize, double[] centdata){
            long startTime = System.currentTimeMillis();

            int curPos = 0;
            for (Partition<DoubleArray> partition : cenTable
                    .getPartitions()) {
                DoubleArray array = partition.get();
                double[] cData = array.get();
                int start = array.start();
                int size = array.size();
                for (int i = start; i < (start + size); i++) {
                    // Don't set the first element in each row
                    if (i % cenVecSize != 0) {
                        cData[i] = centdata[curPos];
                        curPos++;
                    }
                }
                    }
            long endTime = System.currentTimeMillis();
            LOG.info("Load centroids (ms): "
                    + (endTime - startTime));
            }

        /**
         * Print interia value of the centroids
         * 
         * @param cenDataMap
         * @param vectorSize
         * @param centdata
         */
 
        private void
            printInteria(Table<DoubleArray> cenTable,
                    int cenVecSize){
            long startTime = System.currentTimeMillis();

            int curPos = 0;
            double interia = 0.;
            for (Partition<DoubleArray> partition : cenTable
                    .getPartitions()) {
                DoubleArray array = partition.get();
                double[] cData = array.get();
                int start = array.start();
                int size = array.size();
                for (int i = start; i < (start + size); i++) {
                    // the first element in each row is numpoints
                    if (i % cenVecSize != 0) {
                        interia += cData[i] * cData[i];
                    }
                }
            }
            long endTime = System.currentTimeMillis();
            LOG.info("Iteria of the centroids: " + interia + ","
                    + (endTime - startTime) + "(ms)");

            }


        /**
         * Broadcast centroids data in partitions
         * 
         * @param table
         * @param numPartitions
         * @throws IOException
         */
        private void bcastCentroids(
                Table<DoubleArray> table, int bcastID)
            throws IOException {
            long startTime = System.currentTimeMillis();
            boolean isSuccess = false;
            try {
                isSuccess =
                    this.broadcast("main",
                            "broadcast-centroids", table, bcastID,
                            false);
            } catch (Exception e) {
                LOG.error("Fail to bcast.", e);
            }
            long endTime = System.currentTimeMillis();
            LOG.info("Bcast centroids (ms): "
                    + (endTime - startTime));
            if (!isSuccess) {
                throw new IOException("Fail to bcast");
            }
                }
    }
