package com.advancedspark.streaming.rating.ml

import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.streaming.Seconds
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.SparkContext
import org.apache.spark.sql.SQLContext
import org.apache.spark.SparkConf
import kafka.serializer.StringDecoder
import org.apache.spark.sql.SaveMode
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.Time
import org.apache.spark.ml.recommendation.ALS

import org.elasticsearch.spark.sql._ 

object TrainALSBatch {
  def main(args: Array[String]) {
    val conf = new SparkConf()
      .set("spark.cassandra.connection.host", "127.0.0.1")

    val sc = SparkContext.getOrCreate(conf)

    def createStreamingContext(): StreamingContext = {
      @transient val newSsc = new StreamingContext(sc, Seconds(5))
      println(s"Creating new StreamingContext $newSsc")

      newSsc
    }
    val ssc = StreamingContext.getActiveOrCreate(createStreamingContext)

    val sqlContext = SQLContext.getOrCreate(sc)
    import sqlContext.implicits._

    val brokers = "127.0.0.1:9092"
    val topics = Set("item_ratings")
    val kafkaParams = Map[String, String]("metadata.broker.list" -> brokers)
    val cassandraConfig = Map("keyspace" -> "advancedspark", "table" -> "item_ratings")
    val esConfig = Map("pushdown" -> "true", "es.nodes" -> "127.0.0.1", "es.port" -> "9200")

    val ratingsStream = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](ssc, kafkaParams, topics)

    val htmlHome = sys.env("HTML_HOME")

    val itemsDF = sqlContext.read.format("json")
      .load(s"""file:${htmlHome}/advancedspark.com/json/software.json""")

    ratingsStream.foreachRDD {
      (message: RDD[(String, String)], batchTime: Time) => {
        message.cache()

	// TODO:  We're not using any of the stream data at the moment - just the data in cassandra
        //        This is almost like a cron job at the moment.  Not ideal.  Will fix soon.

        // Read all ratings from Cassandra
        // Note:  Cassandra has been initialized through spark-env.sh
        //        Specifically, export SPARK_JAVA_OPTS=-Dspark.cassandra.connection.host=127.0.0.1
	val itemRatingsDF = sqlContext.read.format("org.apache.spark.sql.cassandra")
	  .options(cassandraConfig).load().toDF("userId", "itemId", "rating", "timestamp").cache()

	// Train the model
   	val rank = 20
	val maxIterations = 5
	val lambdaRegularization = 0.1

	val als = new ALS()
  	  .setRank(rank)
 	  .setRegParam(lambdaRegularization)
  	  .setUserCol("userId")
 	  .setItemCol("itemId")
 	  .setRatingCol("rating")

	val model = als.fit(itemRatingsDF)
        model.setPredictionCol("confidence")

	// Generate top recommendations for everyone in the system (not ideal)
        val recommendationsDF = model.transform(itemRatingsDF.select($"userId", $"itemId"))

	val enrichedRecommendationsDF = 
   	  recommendationsDF.join(itemsDF, $"itemId" === $"id")
   	  .select($"userId", $"itemId", $"title", $"description", $"tags", $"img", $"confidence")
   	  .sort($"userId", $"confidence" desc)

        enrichedRecommendationsDF.write.format("org.elasticsearch.spark.sql").mode(SaveMode.Overwrite)
	  .options(esConfig).save("advancedspark/personalized-als")

	message.unpersist()
      }
    }

    ssc.start()
    ssc.awaitTermination()
  }
}
