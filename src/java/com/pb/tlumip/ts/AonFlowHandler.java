/*
 * Copyright  2005 PB Consult Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.pb.tlumip.ts;

/**
 *
 * @author    Jim Hicks
 * @version   1.0, 6/30/2004
 */


import java.util.HashMap;
import java.util.ResourceBundle;

import com.pb.common.rpc.DBlockingQueue;
import com.pb.common.rpc.DafNode;
import com.pb.common.rpc.RpcException;
import com.pb.common.util.ResourceUtil;

import org.apache.log4j.Logger;



public class AonFlowHandler implements AonFlowHandlerIF {

    protected static Logger logger = Logger.getLogger(AonFlowHandler.class);

    public static final String WORK_QUEUE_NAME = "aonFlowWorkQueue";
    
    public static final int PACKET_SIZE = 100;
    public static final int NUM_NULL_PACKETS_TO_QUEUE = 100;
    

    // set the frequency with which the shared class is polled to see if all threads have finshed their work.
//    static final int POLLING_FREQUENCY_IN_SECONDS = 10;
    static final int POLLING_FREQUENCY_IN_SECONDS = 1;
    
    static String rpcConfigFile = null;
    
    HashMap componentPropertyMap;
    HashMap globalPropertyMap;

    ResourceBundle componentRb;
    ResourceBundle globalRb;
    
    int networkNumLinks;
    int networkNumUserClasses;
    int networkNumCentroids;
    String timePeriod;

    double[][] tripTableRowSums = null;
    
    NetworkHandlerIF nh;
    DemandHandlerIF dh;
    
	public AonFlowHandler() {
    }



    // Factory Method to return either local or remote instance
    public static AonFlowHandlerIF getInstance( String rpcConfigFile ) {
    
        // store config file name in this object so it can be retrieved by others if needed.
        AonFlowHandler.rpcConfigFile = rpcConfigFile;

        
        if ( rpcConfigFile == null ) {

            // if rpc config file is null, then all handlers are local, so return local instance
            return new AonFlowHandler();

        }
        else {
            
            // return either a local instance or an rpc instance depending on how the handler was defined.
            Boolean isLocal = DafNode.getInstance().isHandlerLocal( HANDLER_NAME );

            if ( isLocal == null )
                // handler name not found in config file, so create a local instance.
                return new AonFlowHandler();
            else 
                // handler name found in config file but is not local, so create an rpc instance.
                return new AonFlowHandlerRpc( rpcConfigFile );

        }
        
    }
    

    // Factory Method to return local instance only
    public static AonFlowHandlerIF getInstance() {
        return new AonFlowHandler();
    }
    

    
    public boolean setup( ResourceBundle componentRb, ResourceBundle globalRb, NetworkHandlerIF nh, char[] highwayModeCharacters ) {

        this.componentRb = componentRb;
        this.globalRb = globalRb;

        componentPropertyMap = ResourceUtil.changeResourceBundleIntoHashMap( componentRb );
        globalPropertyMap = ResourceUtil.changeResourceBundleIntoHashMap( globalRb );

        this.nh = nh;
        networkNumLinks = nh.getLinkCount();
        networkNumCentroids = nh.getNumCentroids();
        networkNumUserClasses = nh.getNumUserClasses();
        this.timePeriod = nh.getTimePeriod();

        dh = DemandHandler.getInstance( rpcConfigFile );
        dh.setup( componentRb, globalRb, timePeriod, networkNumCentroids, networkNumUserClasses, nh.getNodeIndex(), nh.getAssignmentGroupMap(), highwayModeCharacters, nh.userClassesIncludeTruck() );
        dh.buildDemandObject();
        
        return true;
        
    }
    
    

    public double[][] getMulticlassAonLinkFlows () {

        // define data structures used to manage the distribution of work
        DBlockingQueue workQueue = new DBlockingQueue(WORK_QUEUE_NAME);
        
        
        // distribute work into packets and put on queue
        try {
            workQueue.clear();
            putWorkPacketsOnQueue( workQueue, dh.getTripTableRowSums() );
        } catch (RpcException e) {
            logger.error ("exception thrown distributing work into packets and putting on workQueue.", e);
        }

        
        // start the distributed handlers, and combine their results when they've all finished.
        double[][] aonFlow = runSpBuildLoadHandlers( dh.getMulticlassTripTables() );
        
        return aonFlow;
        
    }

    
    private double[][] runSpBuildLoadHandlers( double[][][] tripTables ) {

        // get the specific handler names from the config file that begin with the SpBuildLoadHandler handler name.
        String[] spHandlerNames = null;
        if ( rpcConfigFile == null ) {
            spHandlerNames = new String[1];
            spHandlerNames[0] = SpBuildLoadHandlerIF.HANDLER_NAME;
        }
        else {
            spHandlerNames = DafNode.getInstance().getHandlerNamesStartingWith( SpBuildLoadHandlerIF.HANDLER_NAME );

            if ( spHandlerNames == null ) {
                spHandlerNames = new String[1];
                spHandlerNames[0] = SpBuildLoadHandlerIF.HANDLER_NAME;
            }
        }

        // create an array of SpBuildLoadHandlers dimensioned to the number of handler names found
        SpBuildLoadHandlerIF[] sp = new SpBuildLoadHandlerIF[spHandlerNames.length];

        
        // for each handler name, create a SpBuildLoadHandler, set it up, and start it running
        for ( int i=0; i < spHandlerNames.length; i++ ) {
            sp[i] = SpBuildLoadHandler.getInstance( rpcConfigFile, spHandlerNames[i] );
            sp[i].setup( spHandlerNames[i], rpcConfigFile, tripTables );
            sp[i].start();
        }


        // wait for all SpBuildLoadHandlers to ave indicated they are finished.
        waitForAllHandlers( sp );

        
        // all SpBuildLoadHandlers are finished, so get results.
        double[][] aonFlow = new double[networkNumUserClasses][networkNumLinks];

        for ( int i=0; i < spHandlerNames.length; i++ ) {

            double[][] handlerResults = sp[i].getResults();
            for (int m=0; m < handlerResults.length; m++)
                for (int k=0; k < handlerResults[m].length; k++)
                    aonFlow[m][k] += handlerResults[m][k];
            
        }
        
        return aonFlow;
        
    }
    
    
    private void waitForAllHandlers( SpBuildLoadHandlerIF[] sp ) {
        
        // wait here until all distributed handlers have indicated they are finished.
        while ( getNumberOfHandlersCompleted ( sp ) < sp.length ) {

            try {
                Thread.sleep( POLLING_FREQUENCY_IN_SECONDS*1000 );
            }
            catch (InterruptedException e){
                logger.error ( "exception thrown waiting for all SpBuildLoadHandlers to finish.", e);
            }
            
        }
        
    }


    
    private int getNumberOfHandlersCompleted ( SpBuildLoadHandlerIF[] sp ) {

        int numHandlersCompleted = 0;
        for ( int i=0; i < sp.length; i++ )
            if ( sp[i].handlerIsFinished() )
                numHandlersCompleted++;

        return numHandlersCompleted;
        
    }

    
    
    // Work elements consist of a [user class, origin taz].
    // There will by numUserClasses*numCentroids total work elements.
    // Packets of work elements will be created  and placed on a distributed queue
    // to be processed concurrently if possible.
    private int putWorkPacketsOnQueue( DBlockingQueue workQueue, double[][] tripTableRowSums ) throws RpcException {

        int numPackets = 0;
        
        int[][] workElements = null;

        // group individual work elements into packets to be retrieved by the distributed work handlers
        int k=0;
        for (int m=0; m < networkNumUserClasses; m++) {
            for (int i=0; i < networkNumCentroids; i++) {

                if ( k == 0 )
                    workElements = new int[PACKET_SIZE][2];

                if (tripTableRowSums[m][i] > 0.0) {
                    workElements[k][0] = m;
                    workElements[k][1] = i;
                    k++;

                    if ( k == PACKET_SIZE ) {
                        workQueue.put(workElements);
                        numPackets++;
                        k = 0;
                    }

                }
                
            }
        }

        // create a final packet with the remaining elements
        if ( k > 0 && k < PACKET_SIZE ) {

            int[][] lastElements = new int[k][2];
            for ( int j=0; j < k; j++) {
                lastElements[j][0] = workElements[j][0];
                lastElements[j][1] = workElements[j][1];
            }

            workQueue.put(lastElements);
            numPackets++;
            
        }
        
        
        // add a number of null packets.  this number should be at least as large as the
        // potential number of handlers drawing packets from the work queue.
        // each thread in each worker handler will draws packets from the workQueue
        // until it draws a null packet, at which time the thread's run() method
        // will finalize and return.
        int[][] nullPacket = new int[0][];
        for (int i=0; i < NUM_NULL_PACKETS_TO_QUEUE; i++)
            workQueue.put(nullPacket);
            

        return numPackets;
        
    }

}
