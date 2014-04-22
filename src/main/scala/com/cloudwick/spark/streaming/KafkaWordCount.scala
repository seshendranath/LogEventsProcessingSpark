package com.cloudwick.spark.streaming

import com.cloudwick.cassandra.dao.LogEventDAO
import java.net.{URLConnection, MalformedURLException, URL}
import org.apache.spark.streaming._
import org.apache.spark.streaming.kafka._
import java.io.{IOException, InputStreamReader, BufferedReader, File}
import scala.collection.JavaConversions._
import scala.util.parsing.json.JSON

case class LogEvent(ip:String, timestamp:String, requestPage:String, responseCode:Int, responseSize:Int, userAgent:String)

object KafkaWordCount {

  def parseLogEvent(event: String): LogEvent = {
    val LogPattern = """^([\d.]+) (\S+) (\S+) \[(.*)\] \"([^\s]+) (/[^\s]*) HTTP/[^\s]+\" (\d{3}) (\d+) \"([^\"]+)\" \"([^\"]+)\"$""".r
    val m = LogPattern.findAllIn(event)
    if (!m.isEmpty) {
      new LogEvent(
        m.group(1),
        m.group(4),
        m.group(6),
        m.group(7).toInt,
        m.group(8).toInt,
        m.group(10)
      )
    } 
    else {
      null
    }
  }

  def resolveIp(ip: String): (String, String) = {
    val url = "http://api.hostip.info/get_json.php"
    var bufferedReader: BufferedReader = null
    try {
      val geoURL = new URL(url + "?ip=" + ip)
      val connection: URLConnection = geoURL.openConnection()
      bufferedReader = new BufferedReader(new InputStreamReader(connection.getInputStream))
      val parsedResult = JSON.parseFull(bufferedReader.readLine())
      parsedResult match {
        case Some(map: Map[String, String]) => (map("country_name"), map("city"))
        case None => (null, null) // parsing failed
        case other => (null, null) // unknown data structure
      }
    } catch {
      case e: MalformedURLException => {
        println("exeception caught: " + e.printStackTrace())
        (null, null)
      }
      case e: IOException => {
        println("exeception caught: " + e.printStackTrace())
        (null, null)
      }
    } finally {
      if (bufferedReader != null) {
        try {
          bufferedReader.close()
        } catch {
          case _: Throwable =>
        }
      }
    }
  }

  def main(args: Array[String]) {

    val master = args(0)
    val topics = args(1)
    val numThreads = args(2)
    val zkQuorum = args(3)
    val cassandraNodes = args(4)
    val cassandraKeySpace = args(5)
    val cassandraRepFactor = args(6)
    val clientGroup = args(7)
    val sparkHome = args(8)
    val jars = args(9)

    /*
    val master = "spark://Ashriths-MacBook-Pro.local:7077"
    val topics = "apache"
    val numThreads = 2
    val zkQuorum = "localhost:2181"   // Zookeeper quorum (hostname:port,hostname:port)
    val cassandraNodes = Seq("127.0.0.1")
    val cassandraKeySpace = "log_events"
    val cassandraRepFactor = 1
    val clientGroup = "sparkFetcher"  // group id for this consumer

    val local_jars = List(
      "/Users/ashrith/BigData/Spark/spark_code/kafkawordcount/target/scala-2.10/kafkawordcount_2.10-1.0.jar",
      "/Users/ashrith/BigData/Spark/spark_code/kafkawordcount/lib/spark-streaming-kafka_2.10-0.9.1.jar",
      "/Users/ashrith/BigData/Spark/spark_code/kafkawordcount/lib/spark-streaming_2.10-0.9.1.jar",
      "/Users/ashrith/BigData/Spark/spark_code/kafkawordcount/lib/cassandra-driver-core-2.0.1.jar"
    )

    val classpath = local_jars ::: new File("/Users/ashrith/BigData/Spark/spark/lib_managed/jars").listFiles.toList.map(_.toString)
    */

    val classpath = jars.split(":").toList

    println(classpath)

    val movieDAO = new LogEventDAO(cassandraNodes.split(",").toSeq, cassandraKeySpace)
    movieDAO.createSchema(cassandraRepFactor.toInt)

    val sourceDateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    val destDateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")

    val ssc = new StreamingContext(
      master,
      "KafkaWordCount",
      Seconds(10),
      sparkHome, // SPARK_HOME
      classpath // Throw in all jars for now to get out of ClassNotFound errors
    )

    // A stateful operation is one which operates over multiple batches of data. 
    // This includes all window-based operations and the updateStateByKey 
    // operation. Since stateful operations have a dependency on previous batches 
    // of data, they continuously accumulate metadata over time. To clear this 
    // metadata, streaming supports periodic checkpointing by saving intermediate data to HDFS. 
    // ssc.checkpoint("checkpoint")

    // assign equal threads to process each kafka topic
    val topicMap = topics.split(",").map((_, numThreads.toInt)).toMap

    // create an input stream that pulls messages from a kafka broker
    val events = KafkaUtils.createStream(
      ssc,            // StreamingContext object
      zkQuorum,
      clientGroup,
      topicMap  // Map of (topic_name -> numPartitions) to consume, each partition is consumed in its own thread
      // StorageLevel.MEMORY_ONLY_SER_2 // Storage level to use for storing the received objects
    ).map(_._2)

    val logEvents = events
                          .flatMap(_.split("\n")) // take each line of DStream
                          .map(parseLogEvent(_)) // parse that to log event

    // Return a count of views per URL seen in each batch
    val pageCounts = logEvents.map(event => event.requestPage).countByValue()

    // Return a count of status code occurances in each batch interval
    val statusCodeCounts = logEvents.map(event => event.responseCode).countByValue()

    // val pairs = words.map(x => (x, 1L))

    // val wordCounts = pairs.reduceByKeyAndWindow(
    //   _ + _,
    //   _ - _,
    //   Minutes(10),
    //   Seconds(2),
    //   2 // num of tasks
    // )

    // Update the url visits to cassandra
    pageCounts.foreachRDD(rrd => {
      if (rrd.count != 0) {
        println("====================BEGIN=======================")
        println("\nPopular page views in last 10 seconds (%s total):".format(rrd.count()))
        rrd.collect().foreach( result => {
         println("URL: " + result._1 + " | " + "Count: " + result._2)
         movieDAO.updatePageViews(result._1, result._2.toInt)
        })
        println("=====================END========================")
      }
    })

    // Update number of visits per minute to cassandra
    logEvents.foreachRDD(rrd => {
      if (rrd.count != 0) {
        rrd.collect().foreach(event => {
          movieDAO.updateLogVolumeByMinute(
            destDateFormat.format(sourceDateFormat.parse(event.timestamp)),
            1
          )
        })
      }
    })

    // Count the occurrence of status codes
    statusCodeCounts.foreachRDD(rrd => {
      if (rrd.count != 0) {
        rrd.collect().foreach(result => {
          movieDAO.updateStatusCounter(
            result._1.toInt,
            result._2.toInt
          )
        })
      }
    })

    // Update country stats
    logEvents.foreachRDD(rrd => {
      if (rrd.count != 0) {
        rrd.collect().foreach(event => {
          val geoLocation = resolveIp(event.ip)
          movieDAO.updateVisitsByCountry(
            geoLocation._1,
            geoLocation._2,
            1
          )
        })
      }
    })

    ssc.start()
    ssc.awaitTermination()
  }
}