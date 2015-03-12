/*
 * Copyright 2014 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.twitter.cassovary

import com.twitter.cassovary.graph._
import com.twitter.cassovary.util.io.{AdjacencyListGraphReader, ListOfEdgesGraphReader}
import com.twitter.app.Flags
import com.twitter.util.Stopwatch
import java.io.File
import scala.collection.mutable.ListBuffer

/**
 * Performance test.
 *
 * Performs PageRank and Personalized PageRank algorithms
 * on several real life graphs from http://snap.stanford.edu/data/. Two small
 * graphs are stored under resources, you can benchmark on larger graphs
 * by providing additional graph urls (they will be downloaded).
 *
 * Usage:
 *   PerformanceBenchmark -h
 * to get started.
 *
 * Example Usages:
 * -local=facebook -globalpr
 * Benchmarks global pagerank on the local facebook graph
 *
 * -url=http://snap.stanford.edu/data/cit-HepTh.txt.gz -ppr
 * Downloads the graph from that URL into local subdir cache/ and runs personalized pagerank on it
 *
 * By default runs every test 10 times and reports average time taken.
 *
 * See: [[http://snap.stanford.edu/data/]]
 */

object PerformanceBenchmark extends App with GzipGraphDownloader {
  /**
   * Directory to store cached graphs downloaded from the web.
   */
  val CACHE_DIRECTORY = "cache/"

  /**
   * Path to the directory storing small graphs.
   */
  val SMALL_FILES_DIRECTORY = "src/main/resources/graphs"

  /**
   * Files to be benchmarked as a list of (directory, name) pairs.
   */
  val files = ListBuffer[(String, String)]()

  lazy val smallFiles = List((SMALL_FILES_DIRECTORY, "facebook"), (SMALL_FILES_DIRECTORY, "wiki-Vote"))

  /**
   * Builders of algorithms to be benchmarked.
   */
  val benchmarks = ListBuffer[(DirectedGraph[Node] => OperationBenchmark)]()

  /**
   * Number of repeats of every benchmark.
   */
  val DEFAULT_REPS = 10
  val defaultLocalGraphFile = "facebook"
  val DEFAULT_CENTRALITY_ALGORITHM = "all"

  val flags = new Flags("Performance benchmark")
  val localFileFlag = flags("local", defaultLocalGraphFile,
    "Specify common prefix of local files in " + SMALL_FILES_DIRECTORY)
  val remoteFileFlag = flags("url",
    "http://snap.stanford.edu/data/cit-HepTh.txt.gz",
    "Specify a URL to download a graph file from")
  val helpFlag = flags("h", false, "Print usage")
  val globalPRFlag = flags("globalpr", false, "run global pagerank benchmark")
  val pprFlag = flags("ppr", false, "run personalized pagerank benchmark")
  val centFlag = flags("c", DEFAULT_CENTRALITY_ALGORITHM,
    "run the specified centrality algorithm (indegree, outdegree, closeness, all)")
  val getNodeFlag = flags("gn", 0, "run getNodeById benchmark with a given number of steps")
  val reps = flags("reps", DEFAULT_REPS, "number of times to run benchmark")
  val adjacencyList = flags("a", false, "graph in adjacency list format")
  flags.parseArgs(args)
  if (localFileFlag.isDefined) files += ((SMALL_FILES_DIRECTORY, localFileFlag()))
  if (remoteFileFlag.isDefined) files += cacheRemoteFile(remoteFileFlag())
  if (files.isEmpty) {
    println("No files specified on command line. Taking default graph files facebook and wiki-Vote.")
    files ++= smallFiles
  }
  if (globalPRFlag()) { benchmarks += (g => new PageRankBenchmark(g)) }
  if (pprFlag()) { benchmarks += (g => new PersonalizedPageRankBenchmark(g)) }

  centFlag() match {
    case "indegree"  => benchmarks += (g => new InDegreeCentralityBenchmark(g))
    case "outdegree" => benchmarks += (g => new OutDegreeCentralityBenchmark(g))
    case "closeness" => benchmarks += (g => new ClosenessCentralityBenchmark(g))
    case "all"       => benchmarks.append(g => new InDegreeCentralityBenchmark(g),
      g => new OutDegreeCentralityBenchmark(g), g => new ClosenessCentralityBenchmark(g))
    case s: String => printf("%s is not a valid centrality option.  Please use indegree, outdegree, or closeness.\n", s)
  }

  if (getNodeFlag() > 0) { benchmarks += (g => new GetNodeByIdBenchmark(g, getNodeFlag(),
    GraphDir.OutDir))}
  if (helpFlag()) {
    println(flags.usage)
  } else {
    def readGraph(path : String, filename : String, adjacencyList: Boolean) : DirectedGraph[Node] = {
      if (adjacencyList) {
        AdjacencyListGraphReader.forIntIds(path, filename).toArrayBasedDirectedGraph()
      } else
        ListOfEdgesGraphReader.forIntIds(path, filename).toArrayBasedDirectedGraph()
    }

    if (benchmarks.isEmpty) {
      println("No benchmarks specified on command line. Will only read the local graph files.")
    }

    files.foreach {
      case (path, filename) =>
        printf("Reading %s graph from %s\n", filename, path)
        val readingTime = Stopwatch.start()
        val graph = readGraph(path, filename, adjacencyList())
        printf("\tGraph %s loaded from list of edges with %s nodes and %s edges.\n" +
               "\tLoading Time: %s\n", filename, graph.nodeCount, graph.edgeCount, readingTime())
        for (b <- benchmarks) {
          val benchmark = b(graph)
          printf("Running benchmark %s on graph %s...\n", benchmark.name, filename)
          val duration = benchmark.run(reps())
          printf("\tAvg time over %d repetitions: %s.\n", reps(), duration)
        }
    }
  }

  def cacheRemoteFile(url : String) : (String, String) = {
    printf("Downloading remote file from %s\n", url)
    new File(CACHE_DIRECTORY).mkdirs()
    val name = url.split("/").last.split("\\.")(0) + ".txt"
    val target =  CACHE_DIRECTORY + name
    downloadAndUnpack(url, target)
    (CACHE_DIRECTORY, name)
  }
}
