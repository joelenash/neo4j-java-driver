/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
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
package org.neo4j.driver.v1.integration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.Statement;
import org.neo4j.driver.v1.StatementResultCursor;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.TransactionWork;
import org.neo4j.driver.v1.Value;
import org.neo4j.driver.v1.exceptions.ClientException;
import org.neo4j.driver.v1.exceptions.DatabaseException;
import org.neo4j.driver.v1.exceptions.ServiceUnavailableException;
import org.neo4j.driver.v1.exceptions.SessionExpiredException;
import org.neo4j.driver.v1.exceptions.TransientException;
import org.neo4j.driver.v1.summary.ResultSummary;
import org.neo4j.driver.v1.summary.StatementType;
import org.neo4j.driver.v1.types.Node;
import org.neo4j.driver.v1.util.TestNeo4j;

import static java.util.Collections.emptyIterator;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.neo4j.driver.internal.util.Iterables.single;
import static org.neo4j.driver.internal.util.Matchers.arithmeticError;
import static org.neo4j.driver.internal.util.Matchers.containsResultAvailableAfterAndResultConsumedAfter;
import static org.neo4j.driver.internal.util.Matchers.syntaxError;
import static org.neo4j.driver.v1.Values.parameters;
import static org.neo4j.driver.v1.util.TestUtil.await;
import static org.neo4j.driver.v1.util.TestUtil.awaitAll;

public class SessionAsyncIT
{
    @Rule
    public final TestNeo4j neo4j = new TestNeo4j();

    private Session session;

    @Before
    public void setUp()
    {
        session = neo4j.driver().session();
    }

    @After
    public void tearDown()
    {
        session.closeAsync();
    }

    @Test
    public void shouldRunQueryWithEmptyResult()
    {
        StatementResultCursor cursor = await( session.runAsync( "CREATE (:Person)" ) );

        assertNull( await( cursor.nextAsync() ) );
    }

    @Test
    public void shouldRunQueryWithSingleResult()
    {
        StatementResultCursor cursor = await( session.runAsync( "CREATE (p:Person {name: 'Nick Fury'}) RETURN p" ) );

        Record record = await( cursor.nextAsync() );
        assertNotNull( record );
        Node node = record.get( 0 ).asNode();
        assertEquals( "Person", single( node.labels() ) );
        assertEquals( "Nick Fury", node.get( "name" ).asString() );

        assertNull( await( cursor.nextAsync() ) );
    }

    @Test
    public void shouldRunQueryWithMultipleResults()
    {
        StatementResultCursor cursor = await( session.runAsync( "UNWIND [1,2,3] AS x RETURN x" ) );

        Record record1 = await( cursor.nextAsync() );
        assertNotNull( record1 );
        assertEquals( 1, record1.get( 0 ).asInt() );

        Record record2 = await( cursor.nextAsync() );
        assertNotNull( record2 );
        assertEquals( 2, record2.get( 0 ).asInt() );

        Record record3 = await( cursor.nextAsync() );
        assertNotNull( record3 );
        assertEquals( 3, record3.get( 0 ).asInt() );

        assertNull( await( cursor.nextAsync() ) );
    }

    @Test
    public void shouldFailForIncorrectQuery()
    {
        try
        {
            await( session.runAsync( "RETURN" ) );
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, is( syntaxError( "Unexpected end of input" ) ) );
        }
    }

    @Test
    public void shouldFailWhenQueryFailsAtRuntime()
    {
        StatementResultCursor cursor = await( session.runAsync( "UNWIND [1, 2, 0] AS x RETURN 10 / x" ) );

        Record record1 = await( cursor.nextAsync() );
        assertNotNull( record1 );
        assertEquals( 10, record1.get( 0 ).asInt() );

        Record record2 = await( cursor.nextAsync() );
        assertNotNull( record2 );
        assertEquals( 5, record2.get( 0 ).asInt() );

        try
        {
            await( cursor.nextAsync() );
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, is( arithmeticError() ) );
        }
    }

    @Test
    public void shouldFailWhenServerIsRestarted()
    {
        int queryCount = 10_000;

        String query = "UNWIND range(1, 100) AS x " +
                       "CREATE (n1:Node {value: x})-[r:LINKED {value: x}]->(n2:Node {value: x}) " +
                       "DETACH DELETE n1, n2 " +
                       "RETURN x";

        try
        {
            for ( int i = 0; i < queryCount; i++ )
            {
                StatementResultCursor cursor = await( session.runAsync( query ) );

                if ( i == 0 )
                {
                    neo4j.killDb();
                }

                List<Record> records = await( cursor.listAsync() );
                assertEquals( 100, records.size() );
            }
            fail( "Exception expected" );
        }
        catch ( Throwable t )
        {
            t.printStackTrace();
            assertThat( t, instanceOf( ServiceUnavailableException.class ) );
        }
    }

    @Test
    public void shouldAllowNestedQueries()
    {
        StatementResultCursor cursor =
                await( session.runAsync( "UNWIND [1, 2, 3] AS x CREATE (p:Person {id: x}) RETURN p" ) );

        Future<List<CompletionStage<Record>>> queriesExecuted = runNestedQueries( cursor );
        List<CompletionStage<Record>> futures = await( queriesExecuted );

        List<Record> futureResults = awaitAll( futures );
        assertEquals( 7, futureResults.size() );

        StatementResultCursor personCursor = await( session.runAsync( "MATCH (p:Person) RETURN p ORDER BY p.id" ) );

        List<Node> personNodes = new ArrayList<>();
        Record record;
        while ( (record = await( personCursor.nextAsync() )) != null )
        {
            personNodes.add( record.get( 0 ).asNode() );
        }
        assertEquals( 3, personNodes.size() );

        Node node1 = personNodes.get( 0 );
        assertEquals( 1, node1.get( "id" ).asInt() );
        assertEquals( 10, node1.get( "age" ).asInt() );

        Node node2 = personNodes.get( 1 );
        assertEquals( 2, node2.get( "id" ).asInt() );
        assertEquals( 20, node2.get( "age" ).asInt() );

        Node node3 = personNodes.get( 2 );
        assertEquals( 3, node3.get( "id" ).asInt() );
        assertEquals( 30, personNodes.get( 2 ).get( "age" ).asInt() );
    }

    @Test
    public void shouldAllowMultipleAsyncRunsWithoutConsumingResults()
    {
        int queryCount = 13;
        List<CompletionStage<StatementResultCursor>> cursors = new ArrayList<>();
        for ( int i = 0; i < queryCount; i++ )
        {
            cursors.add( session.runAsync( "CREATE (:Person)" ) );
        }

        List<CompletionStage<Record>> records = new ArrayList<>();
        for ( StatementResultCursor cursor : awaitAll( cursors ) )
        {
            records.add( cursor.nextAsync() );
        }

        awaitAll( records );

        await( session.closeAsync() );
        session = neo4j.driver().session();

        StatementResultCursor cursor = await( session.runAsync( "MATCH (p:Person) RETURN count(p)" ) );
        Record record = await( cursor.nextAsync() );
        assertNotNull( record );
        assertEquals( queryCount, record.get( 0 ).asInt() );
    }

    @Test
    public void shouldExposeStatementKeysForColumnsWithAliases()
    {
        StatementResultCursor cursor = await( session.runAsync( "RETURN 1 AS one, 2 AS two, 3 AS three, 4 AS five" ) );

        assertEquals( Arrays.asList( "one", "two", "three", "five" ), cursor.keys() );
    }

    @Test
    public void shouldExposeStatementKeysForColumnsWithoutAliases()
    {
        StatementResultCursor cursor = await( session.runAsync( "RETURN 1, 2, 3, 5" ) );

        assertEquals( Arrays.asList( "1", "2", "3", "5" ), cursor.keys() );
    }

    @Test
    public void shouldExposeResultSummaryForSimpleQuery()
    {
        String query = "CREATE (:Node {id: $id, name: $name})";
        Value params = parameters( "id", 1, "name", "TheNode" );

        StatementResultCursor cursor = await( session.runAsync( query, params ) );
        ResultSummary summary = await( cursor.summaryAsync() );

        assertEquals( new Statement( query, params ), summary.statement() );
        assertEquals( 1, summary.counters().nodesCreated() );
        assertEquals( 1, summary.counters().labelsAdded() );
        assertEquals( 2, summary.counters().propertiesSet() );
        assertEquals( 0, summary.counters().relationshipsCreated() );
        assertEquals( StatementType.WRITE_ONLY, summary.statementType() );
        assertFalse( summary.hasPlan() );
        assertFalse( summary.hasProfile() );
        assertNull( summary.plan() );
        assertNull( summary.profile() );
        assertEquals( 0, summary.notifications().size() );
        assertThat( summary, containsResultAvailableAfterAndResultConsumedAfter() );
    }

    @Test
    public void shouldExposeResultSummaryForExplainQuery()
    {
        String query = "EXPLAIN CREATE (),() WITH * MATCH (n)-->(m) CREATE (n)-[:HI {id: 'id'}]->(m) RETURN n, m";

        StatementResultCursor cursor = await( session.runAsync( query ) );
        ResultSummary summary = await( cursor.summaryAsync() );

        assertEquals( new Statement( query ), summary.statement() );
        assertEquals( 0, summary.counters().nodesCreated() );
        assertEquals( 0, summary.counters().propertiesSet() );
        assertEquals( 0, summary.counters().relationshipsCreated() );
        assertEquals( StatementType.READ_WRITE, summary.statementType() );
        assertTrue( summary.hasPlan() );
        assertFalse( summary.hasProfile() );
        assertNotNull( summary.plan() );
        // asserting on plan is a bit fragile and can break when server side changes or with different
        // server versions; that is why do fuzzy assertions in this test based on string content
        String planAsString = summary.plan().toString();
        assertThat( planAsString, containsString( "CreateNode" ) );
        assertThat( planAsString, containsString( "Expand" ) );
        assertThat( planAsString, containsString( "AllNodesScan" ) );
        assertNull( summary.profile() );
        assertEquals( 0, summary.notifications().size() );
        assertThat( summary, containsResultAvailableAfterAndResultConsumedAfter() );
    }

    @Test
    public void shouldExposeResultSummaryForProfileQuery()
    {
        String query = "PROFILE CREATE (:Node)-[:KNOWS]->(:Node) WITH * MATCH (n) RETURN n";

        StatementResultCursor cursor = await( session.runAsync( query ) );
        ResultSummary summary = await( cursor.summaryAsync() );

        assertEquals( new Statement( query ), summary.statement() );
        assertEquals( 2, summary.counters().nodesCreated() );
        assertEquals( 0, summary.counters().propertiesSet() );
        assertEquals( 1, summary.counters().relationshipsCreated() );
        assertEquals( StatementType.READ_WRITE, summary.statementType() );
        assertTrue( summary.hasPlan() );
        assertTrue( summary.hasProfile() );
        assertNotNull( summary.plan() );
        assertNotNull( summary.profile() );
        // asserting on profile is a bit fragile and can break when server side changes or with different
        // server versions; that is why do fuzzy assertions in this test based on string content
        String profileAsString = summary.profile().toString();
        assertThat( profileAsString, containsString( "DbHits" ) );
        assertEquals( 0, summary.notifications().size() );
        assertThat( summary, containsResultAvailableAfterAndResultConsumedAfter() );
    }

    @Test
    public void shouldRunAsyncTransactionWithoutRetries()
    {
        InvocationTrackingWork work = new InvocationTrackingWork( "CREATE (:Apa) RETURN 42" );
        CompletionStage<Record> txStage = session.writeTransactionAsync( work );

        Record record = await( txStage );
        assertNotNull( record );
        assertEquals( 42L, record.get( 0 ).asLong() );

        assertEquals( 1, work.invocationCount() );
        assertEquals( 1, countNodesByLabel( "Apa" ) );
    }

    @Test
    public void shouldRunAsyncTransactionWithRetriesOnAsyncFailures()
    {
        InvocationTrackingWork work = new InvocationTrackingWork( "CREATE (:Node) RETURN 24" ).withAsyncFailures(
                new ServiceUnavailableException( "Oh!" ),
                new SessionExpiredException( "Ah!" ),
                new TransientException( "Code", "Message" ) );

        CompletionStage<Record> txStage = session.writeTransactionAsync( work );

        Record record = await( txStage );
        assertNotNull( record );
        assertEquals( 24L, record.get( 0 ).asLong() );

        assertEquals( 4, work.invocationCount() );
        assertEquals( 1, countNodesByLabel( "Node" ) );
    }

    @Test
    public void shouldRunAsyncTransactionWithRetriesOnSyncFailures()
    {
        InvocationTrackingWork work = new InvocationTrackingWork( "CREATE (:Test) RETURN 12" ).withSyncFailures(
                new TransientException( "Oh!", "Deadlock!" ),
                new ServiceUnavailableException( "Oh! Network Failure" ) );

        CompletionStage<Record> txStage = session.writeTransactionAsync( work );

        Record record = await( txStage );
        assertNotNull( record );
        assertEquals( 12L, record.get( 0 ).asLong() );

        assertEquals( 3, work.invocationCount() );
        assertEquals( 1, countNodesByLabel( "Test" ) );
    }

    @Test
    public void shouldRunAsyncTransactionThatCanNotBeRetried()
    {
        InvocationTrackingWork work = new InvocationTrackingWork( "UNWIND [10, 5, 0] AS x CREATE (:Hi) RETURN 10/x" );
        CompletionStage<Record> txStage = session.writeTransactionAsync( work );

        try
        {
            await( txStage );
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( ClientException.class ) );
        }

        assertEquals( 1, work.invocationCount() );
        assertEquals( 0, countNodesByLabel( "Hi" ) );
    }

    @Test
    public void shouldRunAsyncTransactionThatCanNotBeRetriedAfterATransientFailure()
    {
        // first throw TransientException directly from work, retry can happen afterwards
        // then return a future failed with DatabaseException, retry can't happen afterwards
        InvocationTrackingWork work = new InvocationTrackingWork( "CREATE (:Person) RETURN 1" )
                .withSyncFailures( new TransientException( "Oh!", "Deadlock!" ) )
                .withAsyncFailures( new DatabaseException( "Oh!", "OutOfMemory!" ) );
        CompletionStage<Record> txStage = session.writeTransactionAsync( work );

        try
        {
            await( txStage );
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( DatabaseException.class ) );
            assertEquals( 1, e.getSuppressed().length );
            assertThat( e.getSuppressed()[0], instanceOf( TransientException.class ) );
        }

        assertEquals( 2, work.invocationCount() );
        assertEquals( 0, countNodesByLabel( "Person" ) );
    }

    @Test
    public void shouldPeekRecordFromCursor()
    {
        StatementResultCursor cursor = await( session.runAsync( "UNWIND [1, 2, 42] AS x RETURN x" ) );

        assertEquals( 1, await( cursor.peekAsync() ).get( 0 ).asInt() );
        assertEquals( 1, await( cursor.peekAsync() ).get( 0 ).asInt() );
        assertEquals( 1, await( cursor.peekAsync() ).get( 0 ).asInt() );

        assertEquals( 1, await( cursor.nextAsync() ).get( 0 ).asInt() );

        assertEquals( 2, await( cursor.peekAsync() ).get( 0 ).asInt() );
        assertEquals( 2, await( cursor.peekAsync() ).get( 0 ).asInt() );

        assertEquals( 2, await( cursor.nextAsync() ).get( 0 ).asInt() );

        assertEquals( 42, await( cursor.nextAsync() ).get( 0 ).asInt() );

        assertNull( await( cursor.peekAsync() ) );
        assertNull( await( cursor.nextAsync() ) );
    }

    @Test
    public void shouldForEachWithEmptyCursor()
    {
        testForEach( "CREATE ()", 0 );
    }

    @Test
    public void shouldForEachWithNonEmptyCursor()
    {
        testForEach( "UNWIND range(1, 100000) AS x RETURN x", 100000 );
    }

    @Test
    public void shouldConvertToListWithEmptyCursor()
    {
        testList( "MATCH (n:NoSuchLabel) RETURN n", Collections.emptyList() );
    }

    @Test
    public void shouldConvertToListWithNonEmptyCursor()
    {
        testList( "UNWIND range(1, 100, 10) AS x RETURN x",
                Arrays.asList( 1L, 11L, 21L, 31L, 41L, 51L, 61L, 71L, 81L, 91L ) );
    }

    private Future<List<CompletionStage<Record>>> runNestedQueries( StatementResultCursor inputCursor )
    {
        CompletableFuture<List<CompletionStage<Record>>> resultFuture = new CompletableFuture<>();
        runNestedQueries( inputCursor, new ArrayList<>(), resultFuture );
        return resultFuture;
    }

    private void runNestedQueries( StatementResultCursor inputCursor, List<CompletionStage<Record>> stages,
            CompletableFuture<List<CompletionStage<Record>>> resultFuture )
    {
        final CompletionStage<Record> recordResponse = inputCursor.nextAsync();
        stages.add( recordResponse );

        recordResponse.whenComplete( ( record, error ) ->
        {
            if ( error != null )
            {
                resultFuture.completeExceptionally( error );
            }
            else if ( record != null )
            {
                runNestedQuery( inputCursor, record, stages, resultFuture );
            }
            else
            {
                resultFuture.complete( stages );
            }
        } );
    }

    private void runNestedQuery( StatementResultCursor inputCursor, Record record,
            List<CompletionStage<Record>> stages, CompletableFuture<List<CompletionStage<Record>>> resultFuture )
    {
        Node node = record.get( 0 ).asNode();
        long id = node.get( "id" ).asLong();
        long age = id * 10;

        CompletionStage<StatementResultCursor> response =
                session.runAsync( "MATCH (p:Person {id: $id}) SET p.age = $age RETURN p",
                parameters( "id", id, "age", age ) );

        response.whenComplete( ( cursor, error ) ->
        {
            if ( error != null )
            {
                resultFuture.completeExceptionally( error );
            }
            else
            {
                stages.add( cursor.nextAsync() );
                runNestedQueries( inputCursor, stages, resultFuture );
            }
        } );
    }

    private long countNodesByLabel( String label )
    {
        return session.run( "MATCH (n:" + label + ") RETURN count(n)" ).single().get( 0 ).asLong();
    }

    private void testForEach( String query, int expectedSeenRecords )
    {
        StatementResultCursor cursor = await( session.runAsync( query ) );

        final AtomicInteger recordsSeen = new AtomicInteger();
        CompletionStage<Void> forEachDone = cursor.forEachAsync( record -> recordsSeen.incrementAndGet() );

        assertNull( await( forEachDone ) );
        assertEquals( expectedSeenRecords, recordsSeen.get() );
    }

    private <T> void testList( String query, List<T> expectedList )
    {
        StatementResultCursor cursor = await( session.runAsync( query ) );
        List<Record> records = await( cursor.listAsync() );
        List<Object> actualList = new ArrayList<>();
        for ( Record record : records )
        {
            actualList.add( record.get( 0 ).asObject() );
        }
        assertEquals( expectedList, actualList );
    }

    private static class InvocationTrackingWork implements TransactionWork<CompletionStage<Record>>
    {
        final String query;
        final AtomicInteger invocationCount;

        Iterator<RuntimeException> asyncFailures = emptyIterator();
        Iterator<RuntimeException> syncFailures = emptyIterator();

        InvocationTrackingWork( String query )
        {
            this.query = query;
            this.invocationCount = new AtomicInteger();
        }

        InvocationTrackingWork withAsyncFailures( RuntimeException... failures )
        {
            asyncFailures = Arrays.asList( failures ).iterator();
            return this;
        }

        InvocationTrackingWork withSyncFailures( RuntimeException... failures )
        {
            syncFailures = Arrays.asList( failures ).iterator();
            return this;
        }

        int invocationCount()
        {
            return invocationCount.get();
        }

        @Override
        public CompletionStage<Record> execute( Transaction tx )
        {
            invocationCount.incrementAndGet();

            if ( syncFailures.hasNext() )
            {
                throw syncFailures.next();
            }

            CompletableFuture<Record> resultFuture = new CompletableFuture<>();

            tx.runAsync( query ).whenComplete( ( cursor, error ) ->
                    processQueryResult( cursor, error, resultFuture ) );

            return resultFuture;
        }

        private void processQueryResult( StatementResultCursor cursor, Throwable error,
                CompletableFuture<Record> resultFuture )
        {
            if ( error != null )
            {
                resultFuture.completeExceptionally( error );
                return;
            }

            cursor.nextAsync().whenComplete( ( record, fetchError ) ->
                    processFetchResult( record, fetchError, resultFuture ) );
        }

        private void processFetchResult( Record record, Throwable error, CompletableFuture<Record> resultFuture )
        {
            if ( error != null )
            {
                resultFuture.completeExceptionally( error );
                return;
            }

            if ( record == null )
            {
                resultFuture.completeExceptionally( new AssertionError( "Record not available" ) );
                return;
            }

            if ( asyncFailures.hasNext() )
            {
                resultFuture.completeExceptionally( asyncFailures.next() );
            }
            else
            {
                resultFuture.complete( record );
            }
        }
    }
}
