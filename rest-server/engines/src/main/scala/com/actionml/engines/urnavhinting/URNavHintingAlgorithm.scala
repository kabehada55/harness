/*
 * Copyright ActionML, LLC under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * ActionML licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.actionml.engines.urnavhinting

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import com.actionml.core.drawInfo
import com.actionml.core.engine._
import com.actionml.core.model.{GenericQuery, GenericQueryResult}
import com.actionml.core.spark.{SparkContextSupport, SparkMongoSupport}
import com.actionml.core.validate.{JsonParser, MissingParams, ValidateError, WrongParams}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext, rdd}
import org.bson.Document
import org.joda.time.DateTime
import com.actionml.core.store.backends.MongoStorage
import com.actionml.engines.urnavhinting.URNavHintingAlgorithm.URAlgorithmParams
import com.actionml.engines.urnavhinting.URNavHintingEngine.{ItemProperties, URNavHintingEvent, URNavHintingQuery, URNavHintingQueryResult}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext.Implicits.global
import org.apache.mahout.math.cf.{DownsamplableCrossOccurrenceDataset, SimilarityAnalysis}
import org.apache.mahout.math.indexeddataset.IndexedDataset
import org.apache.mahout.sparkbindings.indexeddataset.IndexedDatasetSpark
import org.json4s.JsonAST.{JDouble, JValue}

import scala.concurrent.duration.Duration


/** Scafolding for a Kappa Algorithm, change with KappaAlgorithm[T] to with LambdaAlgorithm[T] to switch to Lambda,
  * and mixing is allowed since they each just add either real time "input" or batch "train" methods. It is sometimes
  * possible to make use of real time input in a LambdaAlgorithm such as the Universal Recommender making real time
  * changes to item attributes directly in the model rather than waiting for a training task.
  * This is not the minimal Template because many methods are implemented generically in the
  * base classes but is better used as a starting point for new Engines.
  */
class URNavHintingAlgorithm private (engine: URNavHintingEngine, initParams: String, dataset: URNavHintingDataset, params: URAlgorithmParams)
  extends Algorithm[URNavHintingQuery, URNavHintingQueryResult]
  with LambdaAlgorithm[URNavHintingEvent]
  with SparkMongoSupport
  with JsonParser {

  import URNavHintingAlgorithm._

  private var sparkContext: Validated[ValidateError, SparkContext] = _

  case class BoostableCorrelators(actionName: String, itemIDs: Seq[String], boost: Option[Float] = None) {
    def toFilterCorrelators: FilterCorrelators = {
      FilterCorrelators(actionName, itemIDs)
    }
  }
  case class FilterCorrelators(actionName: String, itemIDs: Seq[String])
  case class ExclusionFields(propertyName: String, values: Seq[String])

  // internal settings, these are setup from init and may be changed by a `harness update <engine-id>` command
  private var recsModel: String = _
  private var userBias: Float = _
  private var itemBias: Float = _
  private var maxQueryEvents: Int = _
  private var indicatorParams: Map[String, DefaultIndicatorParams] = _
  private var limit: Int = _
  private var modelEventNames: Seq[String] = _
  private var blacklistEvents: Seq[String] = _
  private var returnSelf: Boolean = _
  private var fields: Seq[Field] = _
  private var randomSeed: Int = _
  private var numESWriteConnections: Option[Int] = _
  private var maxCorrelatorsPerEventType: Int = _
  private var maxEventsPerEventType: Int = _
  private var rankingsParams: Seq[RankingParams] = _
  private var rankingFieldNames: Seq[String] = _
  private var dateNames: Seq[String] = _

  // setting that cannot change with config
  val esIndex = engineId
  val esType = "items"

  def initSettings(params: URAlgorithmParams): Validated[ValidateError, Boolean] = {
    var err: Validated[ValidateError, Boolean] = Valid(true)

    recsModel = params.recsModel.getOrElse(DefaultURAlgoParams.RecsModel)
    //val eventNames: Seq[String] = params.eventNames

    userBias = params.userBias.getOrElse(1f)
    itemBias = params.itemBias.getOrElse(1f)

    // get max total user history events for the ES query
    maxQueryEvents = if (params.indicators.isEmpty) {
      params.maxQueryEvents.getOrElse(DefaultURAlgoParams.MaxQueryEvents)
    } else { // using the indicator method of setting query events
      params.indicators.get.foldLeft[Int](0) { (previous, indicator) =>
        previous + indicator.maxItemsPerUser.getOrElse(DefaultURAlgoParams.MaxQueryEvents)
      } * 10
      // this assumes one event doesn't happen more than 10 times more often than another
      // not ideal but avoids one query to the event store per event type
    }

    if (params.eventNames.nonEmpty) { // using eventNames shortcut
      indicatorParams = params.eventNames.get.map { eventName =>
        eventName -> DefaultIndicatorParams()
      }.toMap
    } else if (params.indicators.nonEmpty) { // using indicators for fined tuned control
      indicatorParams = params.indicators.get.map { indicatorParams =>
        indicatorParams.name -> DefaultIndicatorParams(
          maxItemsPerUser = indicatorParams.maxItemsPerUser.getOrElse(DefaultURAlgoParams.MaxEventsPerEventType),
          maxCorrelatorsPerItem = indicatorParams.maxCorrelatorsPerItem.getOrElse(DefaultURAlgoParams.MaxCorrelatorsPerEventType),
          minLLR = indicatorParams.minLLR)
      }.toMap
    } else {
      logger.error("Must have either \"eventNames\" or \"indicators\" in algorithm parameters, which are: " +
        s"$params")
      err = Invalid(MissingParams("Must have either \"eventNames\" or \"indicators\" in algorithm parameters, which are: " +
        s"$params"))
    }

    // continue validating if all is ok so far
    err.andThen { isOK =>
      limit = params.num.getOrElse(DefaultURAlgoParams.NumResults)

      modelEventNames = if (params.indicators.isEmpty) { //already know from above that one collection has names
        params.eventNames.get
      } else {
        params.indicators.get.map(_.name)
      }.toSeq

      blacklistEvents = params.blacklistEvents.getOrElse(Seq(modelEventNames.head)) // empty Seq[String] means no blacklist
      returnSelf = params.returnSelf.getOrElse(DefaultURAlgoParams.ReturnSelf)
      fields = params.fields.getOrElse(Seq.empty[Field])

      randomSeed = params.seed.getOrElse(System.currentTimeMillis()).toInt

      numESWriteConnections = params.numESWriteConnections

      maxCorrelatorsPerEventType = params.maxCorrelatorsPerEventType
        .getOrElse(DefaultURAlgoParams.MaxCorrelatorsPerEventType)
      maxEventsPerEventType = params.maxEventsPerEventType
        .getOrElse(DefaultURAlgoParams.MaxEventsPerEventType)

      // Unique by 'type' ranking params, if collision get first.
      rankingsParams = params.rankings.getOrElse(Seq(RankingParams(
        name = Some(DefaultURAlgoParams.BackfillFieldName),
        `type` = Some(DefaultURAlgoParams.BackfillType),
        eventNames = Some(modelEventNames.take(1)),
        offsetDate = None,
        endDate = None,
        duration = Some(DefaultURAlgoParams.BackfillDuration)))).groupBy(_.`type`).map(_._2.head).toSeq

      rankingFieldNames = rankingsParams map { rankingParams =>
        val rankingType = rankingParams.`type`.getOrElse(DefaultURAlgoParams.BackfillType)
        val rankingFieldName = rankingParams.name.getOrElse(PopModel.nameByType(rankingType))
        rankingFieldName
      }

      dateNames = Seq(
        params.dateName,
        params.availableDateName,
        params.expireDateName).collect { case Some(date) => date } distinct

      drawInfo("URNavHintingAlgorithm initialization parameters including \"defaults\"", Seq(
        ("════════════════════════════════════════", "══════════════════════════════════════"),
        ("ES index name:", esIndex),
        ("ES type name:", esType),
        ("RecsModel:", recsModel),
        ("Event names:", modelEventNames),
        ("════════════════════════════════════════", "══════════════════════════════════════"),
        ("Random seed:", randomSeed),
        ("MaxCorrelatorsPerEventType:", maxCorrelatorsPerEventType),
        ("MaxEventsPerEventType:", maxEventsPerEventType),
        ("BlacklistEvents:", blacklistEvents),
        ("════════════════════════════════════════", "══════════════════════════════════════"),
        ("User bias:", userBias),
        ("Item bias:", itemBias),
        ("Max query events:", maxQueryEvents),
        ("Limit:", limit),
        ("════════════════════════════════════════", "══════════════════════════════════════"),
        ("Rankings:", "")) ++ rankingsParams.map(x => (x.`type`.get, x.name)))

      Valid(isOK)
    }
  }


    /** Be careful to call super.init(...) here to properly make some Engine values available in scope */
  override def init(engine: Engine): Validated[ValidateError, Boolean] = {
    super.init(engine).andThen { _ =>
      parseAndValidate[URAlgorithmParams](
        initParams,
        errorMsg = s"Error in the Algorithm part of the JSON config for engineId: $engineId, which is: " +
          s"$initParams",
        transform = _ \ "algorithm").andThen { params =>
        // Setup defaults for various params that are not set in the JSON config

        initSettings(params)
      }
    }
  }

  override def destroy(): Unit = {
    // todo: delete the model, only the algorithm knows where it is
  }

  override def input(datum: URNavHintingEvent): Validated[ValidateError, Boolean] = {
    logger.info("Some events may cause the UR to immediately modify the model, like property change events." +
      " This is where that will be done")
    // This deals with real-time model changes.
    // todo: none do anything for the PoC so all return errors
    datum.event match {
      // Here is where you process by reserved events which may modify the model in real-time
      case "$set" =>
        Invalid(WrongParams("Using $set not supported"))
      case "$delete" =>
        datum.entityType match {
          case "user" =>
            logger.warn("Delete a \"user\" not supported")
            Invalid(WrongParams("Using $delele on \"entityType\": \"user\" is not supported yet"))
          case "model" =>
            logger.warn("Delete a \"model\" not supported")
            Invalid(WrongParams("Using $delele on \"entityType\": \"model\" is not supported yet"))
          case _ =>
            logger.warn(s"Deleting unknown entityType is not supported.")
            Invalid(WrongParams(s"Deleting unknown entityType is not supported."))
        }

      case _ =>
      // already processed by the dataset, only model changing event processed here
        Valid(true)
    }
  }

  override def train(): Validated[ValidateError, String] = {
    val defaults = Map(
      "appName" -> engineId,
      "spark.mongodb.input.uri" -> MongoStorage.uri,
      "spark.mongodb.input.database" -> dataset.getItemsDbName,
      "spark.mongodb.input.collection" -> dataset.getIndicatorEventsCollectionName
    )

    SparkContextSupport.getSparkContext(initParams, defaults).map { implicit sc =>

      // todo: we should be able to pass in the dbName and collectionName to any readRdd call now, not tested
      val eventsRdd = readRdd[URNavHintingEvent](sc, MongoStorageHelper.codecs)

      // todo: this should work but not tested and not used in any case
      /*
      val fieldsRdd = readRdd[ItemProperties](sc, MongoStorageHelper.codecs, Some(dataset.getItemsDbName), Some(dataset.getItemsCollectionName)).map { itemProps =>
        (itemProps._id, itemProps.properties)
      }
      */

      val data = getIndicators(modelEventNames, eventsRdd)

      logger.info("======================================== Contents of Indicators ========================================")
      data.actions.foreach { case(name, id) =>
        val ids = id.asInstanceOf[IndexedDatasetSpark]
        logger.info(s"Event name: $name")
        logger.info(s"Num users/rows = ${ids.matrix.nrow}")
        logger.info(s"Num items/columns = ${ids.matrix.ncol}")
        logger.info(s"User dictionary: ${ids.rowIDs.toMap.keySet}")
        logger.info(s"Item dictionary: ${ids.columnIDs.toMap.keySet}")
      }
      logger.info("======================================== done ========================================")

      // todo: for now ignore properties and only calc popularity, then save to ES
      calcAll(data, eventsRdd).save(dateNames, esIndex, esType, numESWriteConnections)
      /* not needed for PoC
      recsModel match {
        case RecsModels.All => calcAll(data)
        case RecsModels.CF => calcAll(data, calcPopular = false)(sc)
        //case RecsModels.BF => calcPop(data)(sc)
        // error, throw an exception
        case unknownRecsModel =>
          throw new IllegalArgumentException(
            s"""
             |Bad algorithm param recsModel=[$unknownRecsModel] in engine definition params, possibly a bad json value.
             |Use one of the available parameter values ($recsModel).""".stripMargin)
      }
      */
    }
    // todo: EsClient.close() can't be done because the Spark driver might be using it unless its done in the Furute
    // with access to `sc`


    logger.debug(s"Starting train $this with spark $sparkContext")
    Valid("Started train Job on Spark")
  }

  def getIndicators(
    modelEventNames: Seq[String],
    eventsRdd: RDD[URNavHintingEvent])
    (implicit sc: SparkContext): PreparedData = {
    URNavHintingPreparator.prepareData(modelEventNames, eventsRdd)
  }

  /** Calculates recs model as well as popularity model */
  def calcAll(
    data: PreparedData,
    eventsRdd: RDD[URNavHintingEvent],
    // todo: ignore properties for now
    // fieldsRdd: RDD[(String, Map[String, Any])],
    calcPopular: Boolean = true)
    (implicit sc: SparkContext): URNavHintingModel = {

    logger.info("Events read now creating correlators")
    // todo: disabling downsampling !!! this ignores indicator params, like minLLR !!!
    // val cooccurrenceIDSs = if (modelEventNames.isEmpty) { // using one global set of algo params
    val cooccurrenceIDSs = if (true) { // using one global set of algo params
      val tmpModel = SimilarityAnalysis.cooccurrencesIDSs(
        data.actions.map(_._2).toArray,
        randomSeed,
        maxInterestingItemsPerThing = maxCorrelatorsPerEventType,
        maxNumInteractions = maxEventsPerEventType)
      logger.info("======================================== Model data ========================================")
      tmpModel.foreach { id =>
        val ids = id.asInstanceOf[IndexedDatasetSpark]
        logger.info(s"Num conversion items/rows = ${ids.matrix.ncol}")
        logger.info(s"Num columns for this segment= ${ids.matrix.nrow}")
        logger.info(s"Row dictionary: ${ids.rowIDs.toMap.keySet}")
        logger.info(s"Column dictionary: ${ids.columnIDs.toMap.keySet}")
      }
      logger.info("======================================== done ========================================")
      tmpModel
    } else { // using params per matrix pair, these take the place of eventNames, maxCorrelatorsPerEventType,
      // and maxEventsPerEventType!
      val indicators = params.indicators.get
      val iDs = data.actions.map(_._2)
      val datasets = iDs.zipWithIndex.map {
        case (iD, i) =>
          new DownsamplableCrossOccurrenceDataset(
            iD,
            indicators(i).maxItemsPerUser.getOrElse(DefaultURAlgoParams.MaxEventsPerEventType),
            indicators(i).maxCorrelatorsPerItem.getOrElse(DefaultURAlgoParams.MaxCorrelatorsPerEventType),
            indicators(i).minLLR)
      }.toList

      logger.info("======================================== Downsampling data ========================================")
      datasets.foreach { id =>
        val ids = id.asInstanceOf[IndexedDatasetSpark]
        logger.info(s"Num users/rows = ${ids.matrix.ncol}")
        logger.info(s"Num items/columns = ${ids.matrix.nrow}")
        logger.info(s"Row dictionary: ${ids.rowIDs.toMap.keySet}")
        logger.info(s"Column dictionary: ${ids.columnIDs.toMap.keySet}")
      }
      logger.info("======================================== done ========================================")



      val tmpModel = SimilarityAnalysis.crossOccurrenceDownsampled(
        datasets,
        randomSeed)
        .map(_.asInstanceOf[IndexedDatasetSpark])

      logger.info("======================================== Model data ========================================")
      tmpModel.foreach { id =>
        val ids = id.asInstanceOf[IndexedDatasetSpark]
        logger.info(s"Num conversion items/rows = ${ids.matrix.ncol}")
        logger.info(s"Num columns for this segment= ${ids.matrix.nrow}")
        logger.info(s"Row dictionary: ${ids.rowIDs.toMap.keySet}")
        logger.info(s"Column dictionary: ${ids.columnIDs.toMap.keySet}")
      }
      logger.info("======================================== done ========================================")



      tmpModel
    }

    val cooccurrenceCorrelators = cooccurrenceIDSs.zip(data.actions.map(_._1)).map(_.swap) //add back the actionNames

    /*
    dataset.getItemsDao.saveOne(ItemProperties("item-1", Map[String, Any](("prop-1" -> 1), ("prop-2" -> Seq("str1", "str2", "str3")))))

    val propsRdd = readRdd[ItemProperties](sc, MongoStorageHelper.codecs, dbName = Some(dataset.getItemsDbName), colName = Some(dataset.getItemsCollectionName))

    logger.info("========================================== Just testing Spark reading another collection ========================================")
    logger.info(s"Got and Rdd for collection: ${dataset.getItemsCollectionName}, which is where the populatiry rank will be")
    propsRdd.collect().foreach { ip =>
      logger.info(s"Item: ${ip._id} properties: ${ip.properties}")
    }
    logger.info(s"Item Properties has ${propsRdd.count()} items")
    */

    val propertiesRDD: RDD[(String, Map[String, Any])] = if (calcPopular) {
      getRanksRDD(eventsRdd)
      /*
      val ranksRdd = getRanksRDD(eventsRdd)
      data.fieldsRDD.fullOuterJoin(ranksRdd).map {
        case (item, (Some(fieldsPropMap), Some(rankPropMap))) => item -> (fieldsPropMap ++ rankPropMap)
        case (item, (Some(fieldsPropMap), None))              => item -> fieldsPropMap
        case (item, (None, Some(rankPropMap)))                => item -> rankPropMap
        case (item, _)                                        => item -> Map.empty
      }
      */
    } else {
      sc.emptyRDD
    }

    logger.info("Correlators created now putting into URNavHintingModel")
    //
    new URNavHintingModel(
      coocurrenceMatrices = cooccurrenceCorrelators,
      propertiesRDDs = Seq(propertiesRDD),
      typeMappings = getMappings)

  }


  def query(query: URNavHintingQuery): URNavHintingQueryResult = {
    URNavHintingQueryResult()
  }

  /** Calculate all fields and items needed for ranking.
    *
    *  @param fieldsRDD all items with their fields
    *  @param sc the current Spark context
    *  @return
    */
  def getRanksRDD(
    // todo: ignore properties for now, just do popularity
    // fieldsRDD: RDD[(String, Map[String, JValue])],
    eventsRdd: RDD[URNavHintingEvent])
    (implicit sc: SparkContext): RDD[(String, Map[String, Any])] = {

    val popModel = new PopModel()
    val rankRDDs: Seq[(String, RDD[(String, Double)])] = rankingsParams map { rankingParams =>
      val rankingType = rankingParams.`type`.getOrElse(DefaultURAlgoParams.BackfillType)
      val rankingFieldName = rankingParams.name.getOrElse(PopModel.nameByType(rankingType))
      val durationAsString = rankingParams.duration.getOrElse(DefaultURAlgoParams.BackfillDuration)
      val duration = Duration(durationAsString).toSeconds.toInt
      val backfillEvents = rankingParams.eventNames.getOrElse(modelEventNames.take(1))
      val offsetDate = rankingParams.offsetDate
      val rankRdd = popModel.calc(
        modelName = rankingType,
        eventNames = backfillEvents,
        eventsRdd = eventsRdd,
        duration,
        offsetDate)

      rankingFieldName -> rankRdd
    }

    //    logger.debug(s"RankRDDs[${rankRDDs.size}]\n${rankRDDs.map(_._1).mkString(", ")}\n${rankRDDs.map(_._2.take(25).mkString("\n")).mkString("\n\n")}")
    rankRDDs.foldLeft[RDD[(String, Map[String, Any])]](sc.emptyRDD) {
      case (leftRdd, (fieldName, rightRdd)) =>
        leftRdd.fullOuterJoin(rightRdd).map {
          case (itemId, (Some(propMap), Some(rank))) => itemId -> (propMap + (fieldName -> JDouble(rank)))
          case (itemId, (Some(propMap), None))       => itemId -> propMap
          case (itemId, (None, Some(rank)))          => itemId -> Map(fieldName -> JDouble(rank))
          case (itemId, _)                           => itemId -> Map.empty
        }
    }
  }


  def getMappings: Map[String, (String, Boolean)] = {
    val mappings = rankingFieldNames.map { fieldName =>
      fieldName -> ("float", false)
    }.toMap ++ // create mappings for correlators, where the Boolean says to not use norms
      modelEventNames.map { correlator =>
        correlator -> ("keyword", true) // use norms with correlators to get closer to cosine similarity.
      }.toMap ++
      dateNames.map { dateName =>
        dateName -> ("date", false) // map dates to be interpreted as dates
      }
    logger.info(s"Index mappings for the Elasticsearch URNavHintingModel: $mappings")
    mappings
  }

}

object URNavHintingAlgorithm extends JsonParser {

  def apply(engine: URNavHintingEngine, initParams: String, dataset: URNavHintingDataset): URNavHintingAlgorithm = {

    val params = parseAndValidate[URAlgorithmParams](initParams, transform = _ \ "algorithm").andThen { params =>
      Valid(true, params)
    }.map(_._2).getOrElse(null.asInstanceOf[URAlgorithmParams])
    new URNavHintingAlgorithm(engine, initParams, dataset, params)
  }

  /** Available value for algorithm param "RecsModel" */
  object RecsModels { // todo: replace this with rankings
    val All = "all"
    val CF = "collabFiltering"
    val BF = "backfill"
    override def toString: String = s"$All, $CF, $BF"
  }

  /** Setting the option in the params case class doesn't work as expected when the param is missing from
    *  engine.json so set these for use in the algorithm when they are not present in the engine.json
    */
  object DefaultURAlgoParams {
    val ModelType = "items"
    val MaxEventsPerEventType = 500
    val NumResults = 20
    val MaxCorrelatorsPerEventType = 50
    val MaxQueryEvents = 100 // default number of user history events to use in recs query

    val ExpireDateName = "expireDate" // default name for the expire date property of an item
    val AvailableDateName = "availableDate" //defualt name for and item's available after date
    val DateName = "date" // when using a date range in the query this is the name of the item's date
    val RecsModel = RecsModels.All // use CF + backfill
    //val RankingParams = RankingParams()
    val BackfillFieldName = RankingFieldName.PopRank
    val BackfillType = RankingType.Popular
    val BackfillDuration = "3650 days" // for all time

    val ReturnSelf = false
    val NumESWriteConnections: Option[Int] = None
  }

  case class RankingParams(
      name: Option[String] = None,
      `type`: Option[String] = None, // See [[com.actionml.BackfillType]]
      eventNames: Option[Seq[String]] = None, // None means use the algo eventNames findMany, otherwise a findMany of events
      offsetDate: Option[String] = None, // used only for tests, specifies the offset date to start the duration so the most
      // recent date for events going back by from the more recent offsetDate - duration
      endDate: Option[String] = None,
      duration: Option[String] = None) { // duration worth of events to use in calculation of backfill
    override def toString: String = {
      s"""
         |_id: $name,
         |type: ${`type`},
         |eventNames: $eventNames,
         |offsetDate: $offsetDate,
         |endDate: $endDate,
         |duration: $duration
      """.stripMargin
    }
  }

  case class DefaultIndicatorParams(
      maxItemsPerUser: Int = DefaultURAlgoParams.MaxQueryEvents, // defaults to maxEventsPerEventType
      maxCorrelatorsPerItem: Int = DefaultURAlgoParams.MaxCorrelatorsPerEventType,
      // defaults to maxCorrelatorsPerEventType
      minLLR: Option[Double] = None) // defaults to none, takes precendence over maxCorrelatorsPerItem

  case class IndicatorParams(
      name: String, // must match one in eventNames
      maxItemsPerUser: Option[Int], // defaults to maxEventsPerEventType
      maxCorrelatorsPerItem: Option[Int], // defaults to maxCorrelatorsPerEventType
      minLLR: Option[Double]) // defaults to none, takes precendence over maxCorrelatorsPerItem

  case class URAlgorithmParams(
      indexName: Option[String], // can optionally be used to specify the elasticsearch index name
      typeName: Option[String], // can optionally be used to specify the elasticsearch type name
      recsModel: Option[String] = None, // "all", "collabFiltering", "backfill"
      eventNames: Option[Seq[String]], // names used to ID all user actions
      blacklistEvents: Option[Seq[String]] = None, // None means use the primary event, empty array means no filter
      // number of events in user-based recs query
      maxQueryEvents: Option[Int] = None,
      maxEventsPerEventType: Option[Int] = None,
      maxCorrelatorsPerEventType: Option[Int] = None,
      num: Option[Int] = None, // default max # of recs requested
      userBias: Option[Float] = None, // will cause the default search engine boost of 1.0
      itemBias: Option[Float] = None, // will cause the default search engine boost of 1.0
      returnSelf: Option[Boolean] = None, // query building logic defaults this to false
      fields: Option[Seq[Field]] = None, //defaults to no fields
      // leave out for default or popular
      rankings: Option[Seq[RankingParams]] = None,
      // name of date property field for when the item is available
      availableDateName: Option[String] = None,
      // name of date property field for when an item is no longer available
      expireDateName: Option[String] = None,
      // used as the subject of a dateRange in queries, specifies the name of the item property
      dateName: Option[String] = None,
      indicators: Option[List[IndicatorParams]] = None, // control params per matrix pair
      seed: Option[Long] = None, // seed is not used presently
      numESWriteConnections: Option[Int] = None) // hint about how to coalesce partitions so we don't overload ES when
  // writing the model. The rule of thumb is (numberOfNodesHostingPrimaries * bulkRequestQueueLength) * 0.75
  // for ES 1.7 bulk queue is defaulted to 50
  //  extends Params //fixed default make it reproducible unless supplied

  /** This file contains case classes that are used with reflection to specify how query and config
    * JSON is to be parsed. the Query case class, for instance defines the way a JSON query is to be
    * formed. The same for param case classes.
    */

  /** The Query spec with optional values. The only hard rule is that there must be either a user or
    *  an item id. All other values are optional.
    */

  /** Used to specify how Fields are represented in engine.json */
  case class Field( // no optional values for fields, whne specified
      name: String, // name of metadata field
      values: Seq[String], // fields can have multiple values like tags of a single value as when using hierarchical
      // taxonomies
      bias: Float) // any positive value is a boost, negative is an inclusion filter, 0 is an exclusion filter
    extends Serializable

  /** Used to specify the date range for a query */
  case class DateRange(
      name: String, // name of item property for the date comparison
      before: Option[String], // empty strings means no filter
      after: Option[String]) // both empty should be ignored
    extends Serializable

  case class PreparedData(
      actions: Seq[(String, IndexedDataset)]) extends Serializable


}

