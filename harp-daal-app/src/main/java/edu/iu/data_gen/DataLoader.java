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

package edu.iu.data_gen;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.FileReader;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.util.ArrayList;
import java.util.Random;

public final class DataLoader
{
    protected static final Log LOG = LogFactory
        .getLog(DataLoader.class);

    private DataLoader() {
    }

    /**
     * Load data points from a file.
     * 
     * @param file
     * @param conf
     * @return
     * @throws IOException
     */
    public static double[] loadPoints(String file,
            int pointsPerFile, int cenVecSize,
            Configuration conf) throws Exception {
        double[] points =
            new double[pointsPerFile * cenVecSize];
        Path pointFilePath = new Path(file);
        FileSystem fs =
            pointFilePath.getFileSystem(conf);
        FSDataInputStream in = fs.open(pointFilePath);
        try {
            for (int i = 0; i < points.length;) {
                for (int j = 0; j < cenVecSize; j++) {
                    points[i++] = in.readDouble();
                }
            }
        } finally {
            in.close();
        }
        return points;
    }

    /**
     * @brief load points from a ASCII Matrix Market format 
     * Dense file
     *
     * @param file
     * @param pointsPerFile
     * @param cenVecSize
     * @param conf
     *
     * @return 
     */
    public static double[] loadPointsMMDense(String file,
            int pointsPerFile, int cenVecSize,
            Configuration conf) throws Exception {

        FSDataInputStream in = null;
        BufferedReader reader = null;
        String line = null;
        double[] points = new double[pointsPerFile*cenVecSize];

        try {

            Path pointFilePath = new Path(file);
            FileSystem fs = pointFilePath.getFileSystem(conf);

            in = fs.open(pointFilePath);
            reader = new BufferedReader(new InputStreamReader(in), 1048576);

            for (int i = 0; i < points.length;) {
                for (int j = 0; j < cenVecSize; j++) {
                    if ((line = reader.readLine())!= null)
                        points[i++] = Double.parseDouble(line);
                }
            }

        } finally {

            in.close();
            reader.close();

        }

        return points;
    }

    /**
     * @bridf load points from yfcc vgg feature files
     * id   vgg 4096    points
     *
     */
    public static double[] loadPointsYFCC100M(String file,
            int pointsPerFile, int cenVecSize,
            Configuration conf) throws Exception {

        FSDataInputStream in = null;
        BufferedReader reader = null;
        String line = null;
        //double[] points = new double[pointsPerFile*cenVecSize];

        //ArrayList<Double> points = new ArrayList<Double>();
        //ArrayList<String> ids = new ArrayList<String>();

        Path pointFilePath = new Path(file);
        FileSystem fs = pointFilePath.getFileSystem(conf);

        in = fs.open(pointFilePath);
        reader = new BufferedReader(new InputStreamReader(in), 1048576);

        //memory compact version
        int BLOCKSIZE = 512*1024*1024/8; //512MB
        double[] points = new double[BLOCKSIZE];
        int pointsCnt = 0;


        int arraySize = 0;
        int yfccFormat = 0;
        while((line = reader.readLine())!= null){
                // line fmt: id \t vgg \t 4096 \t points...
                String[] parts = line.split("\t");

                //
                // hack here: either yfcc100m format or auto gen by random
                //

                if (parts.length ==4){
                    //YFCC format found
                    yfccFormat = 1;
                    //ids.add(parts[0]);
                    for(String w:parts[3].split(" ")){
                        //
                        //points.add( Double.parseDouble(w)) ;
                        points[pointsCnt] = Double.parseDouble(w);
                        pointsCnt ++;
                        arraySize ++;

                        // check array
                        if (pointsCnt == points.length){
                            int newsize = points.length + BLOCKSIZE;
                            double[] newpoints = new double[newsize];
                            System.arraycopy(points, 0, newpoints, 0, points.length);
                            points = newpoints;
                        }
                    }
                }
                else{
                    //random number format
                    //number of points
                    int numPoints = Integer.parseInt(parts[0]);
                    Random r = new Random();
                    for(int i=0; i< numPoints; i++){
                        for(int j=0; j< cenVecSize; j++){
                            points[pointsCnt] = r.nextDouble();
                            pointsCnt ++;
                            arraySize ++;

                            // check array
                            if (pointsCnt == points.length){
                                int newsize = points.length + BLOCKSIZE;
                                double[] newpoints = new double[newsize];
                                System.arraycopy(points, 0, newpoints, 0, points.length);
                                points = newpoints;
                            }
                        }
                    }
                }
        }

        in.close();
        reader.close();

        if (yfccFormat == 1){
            LOG.info("reading data points: " + arraySize);
        }
        else{
            LOG.info("generating data points: " + arraySize);
        }
 
        ////convert into double[]
        double[] pointsRet = new double[arraySize];
        System.arraycopy(points, 0, pointsRet, 0, arraySize);
        
        return pointsRet;
    }

}
