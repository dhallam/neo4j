/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.unsafe.impl.batchimport.store.io;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.neo4j.collection.pool.Pool;
import org.neo4j.helpers.NamedThreadFactory;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.unsafe.impl.batchimport.store.BatchingPageCache.Writer;
import org.neo4j.unsafe.impl.batchimport.store.BatchingPageCache.WriterFactory;

import static java.util.concurrent.TimeUnit.MINUTES;
/**
 * Queue of I/O jobs. A job is basically: "write the contents of ByteBuffer B to channel C starting at position P"
 * Calls to public (interface) methods that this class exposes are assumed to be single-threaded.
 */
public class IoQueue implements WriterFactory
{
    private final ExecutorService executor;
    private final JobMonitor jobMonitor = new JobMonitor();
    private final WriterFactory delegateFactory;

    public IoQueue( int maxIOThreads, WriterFactory delegateFactory )
    {
        this( Executors.newFixedThreadPool( maxIOThreads, new NamedThreadFactory( "IoQueue I/O thread" ) ),
                delegateFactory );
    }

    public IoQueue( ExecutorService executor, WriterFactory delegateFactory )
    {
        this.executor = executor;
        this.delegateFactory = delegateFactory;
    }

    @Override
    public Writer create( File file, StoreChannel channel, Monitor monitor )
    {
        WriteQueue queue = new WriteQueue( executor, jobMonitor);
        return new Funnel( file, channel, monitor, queue );
    }

    @Override
    public void awaitEverythingWritten()
    {
        long endTime = System.currentTimeMillis()+MINUTES.toMillis( 10 );
        while ( jobMonitor.hasActiveJobs() )
        {
            try
            {
                Thread.sleep( 10 );
            }
            catch ( InterruptedException e )
            {
                throw new RuntimeException( e );
            }

            if ( System.currentTimeMillis() > endTime )
            {
                throw new RuntimeException( "Didn't finish within designated time" );
            }
        }
    }

    @Override
    public void shutdown()
    {
        executor.shutdown();
        awaitEverythingWritten();
        try
        {
            executor.awaitTermination( 1, TimeUnit.MINUTES );
        }
        catch ( InterruptedException e )
        {
            throw new RuntimeException( e );
        }
    }

    private class Funnel implements Writer
    {
        private final Writer writer;
        private final WriteQueue queue;

        public Funnel( File file, StoreChannel channel, Monitor monitor, WriteQueue queue )
        {
            this.writer = delegateFactory.create( file, channel, monitor );
            this.queue = queue;
        }

        @Override
        public void write( ByteBuffer byteBuffer, long position, Pool<ByteBuffer> poolToReleaseBufferIn )
                throws IOException
        {
            queue.offer( new WriteJob( writer, byteBuffer, position, poolToReleaseBufferIn ) );
        }
    }
}
