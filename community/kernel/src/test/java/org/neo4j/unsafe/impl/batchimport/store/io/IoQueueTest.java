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
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.kernel.DefaultFileSystemAbstraction;
import org.neo4j.test.CleanupRule;
import org.neo4j.test.TargetDirectory;
import org.neo4j.test.TargetDirectory.TestDirectory;
import org.neo4j.unsafe.impl.batchimport.store.BatchingPageCache.Writer;

import static java.util.concurrent.TimeUnit.SECONDS;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import static org.neo4j.unsafe.impl.batchimport.store.BatchingPageCache.SYNCHRONOUS;


public class IoQueueTest
{
    @Rule
    public final TestDirectory directory = TargetDirectory.testDirForTest( getClass() );
    @Rule
    public final CleanupRule cleanupRule = new CleanupRule();

    private static final FileSystemAbstraction fs = new DefaultFileSystemAbstraction();

    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldExecuteWriteJobsForMultipleFiles() throws Exception
    {
        // GIVEN
        ExecutorService executor = cleanupRule.add( spy( Executors.newFixedThreadPool( 3 ) ) );
        IoQueue queue = new IoQueue( executor, SYNCHRONOUS );
        File file1 = new File( directory.directory(), "file1" );
        StoreChannel channel1 = cleanupRule.add( spy( fs.create( file1 ) ) );
        File file2 = new File( directory.directory(), "file2" );
        StoreChannel channel2 = cleanupRule.add( spy( fs.create( file2 ) ) );
        Monitor monitor = mock( Monitor.class );
        Writer writer1 = queue.create( file1, channel1, monitor );
        Writer writer2 = queue.create( file2, channel2, monitor );
        SimplePool<ByteBuffer> pool1 = mock( SimplePool.class );
        SimplePool<ByteBuffer> pool2 = mock( SimplePool.class );
        ByteBuffer buffer = ByteBuffer.allocate( 10 );
        int position1 = 100, position2 = position1 + buffer.capacity(), position3 = 50;

        // WHEN
        writer1.write( buffer, position1, pool1 );
        writer1.write( buffer, position2, pool1 );
        writer2.write( buffer, position3, pool2 );
        // Depending on race between executor and the job offers, it should be 2-3 invocations
        verify( executor, atLeast( 2 ) ).submit( any( Callable.class ) );
        verify( executor, atMost( 3 ) ).submit( any( Callable.class ) );

        // THEN
        executor.shutdown();
        executor.awaitTermination( 10, SECONDS );
        verify( channel1 ).position( position1 );
        verify( channel1 ).position( position2 );
        verify( channel2 ).position( position3 );
        verify( channel1, times(2) ).write( buffer );
        verify( channel2 ).write( buffer );
        verifyNoMoreInteractions( channel1 );
        verifyNoMoreInteractions( channel2 );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldExecuteWriteJob() throws Exception
    {
        // GIVEN
        ExecutorService executor = cleanupRule.add( spy( Executors.newFixedThreadPool( 3 ) ) );
        IoQueue queue = new IoQueue( executor, SYNCHRONOUS );
        File file = new File( directory.directory(), "file" );
        StoreChannel channel = spy( fs.create( file ) );
        Monitor monitor = mock( Monitor.class );
        Writer writer = queue.create( file, channel, monitor );
        SimplePool<ByteBuffer> pool = Mockito.mock( SimplePool.class );
        ByteBuffer buffer = ByteBuffer.allocate( 10 );
        int position = 100;

        // WHEN
        writer.write( buffer, position, pool );
        verify( executor, times( 1 ) ).submit( any( Callable.class ) );

        // THEN
        executor.shutdown();
        executor.awaitTermination( 10, SECONDS );
        verify( channel ).position( 100 );
        verify( channel ).write( buffer );
        verifyNoMoreInteractions( channel );
    }
}
