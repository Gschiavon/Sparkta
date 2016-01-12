/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.sparkta.aggregator

import scala.util.Try

import akka.event.slf4j.SLF4JLogging
import org.apache.spark.HashPartitioner
import org.apache.spark.streaming.Duration
import org.apache.spark.streaming.dstream.DStream
import org.joda.time.DateTime

import com.stratio.sparkta.sdk._
import com.stratio.sparkta.serving.core.SparktaConfig
import com.stratio.sparkta.serving.core.constants.AppConstant

/**
 * Use this class to describe a cube that you want the multicube to keep.
 *
 * For example, if you're counting events with the dimensions (color, size, flavor) and you
 * want to keep a total count for all (color, size) combinations, you'd specify that using a Cube
 */

case class Cube(name: String,
                dimensions: Seq[Dimension],
                operators: Seq[Operator],
                checkpointTimeDimension: String,
                checkpointInterval: Int,
                checkpointGranularity: String,
                checkpointTimeAvailability: Long) extends SLF4JLogging {

  private val associativeOperators = operators.filter(op => op.isAssociative)
  private lazy val associativeOperatorsMap = associativeOperators.map(op => op.key -> op).toMap
  private val nonAssociativeOperators = operators.filter(op => !op.isAssociative)
  private lazy val nonAssociativeOperatorsMap = nonAssociativeOperators.map(op => op.key -> op).toMap
  private lazy val rememberPartitioner =
    Try(SparktaConfig.getDetailConfig.get.getBoolean(AppConstant.ConfigRememberPartitioner))
      .getOrElse(AppConstant.DefaultRememberPartitioner)
  private final val NotUpdatedValues = 0
  private final val UpdatedValues = 1

  /**
   * Aggregation process that have 4 ways:
   * 1. Cube with associative operators only.
   * 2. Cube with non associative operators only.
   * 3. Cube with associtaive and non associative operators.
   * 4. Cube with no operators.
   */
  //scalastyle:off
  def aggregate(dimensionsValues: DStream[(DimensionValues, InputFieldsValues)])
  : DStream[(DimensionValues, MeasuresValues)] = {

    val filteredValues = filterDimensionValues(dimensionsValues)
    val associativesCalculated = if (associativeOperators.nonEmpty){
      val associativeAgg = associativeAggregation(filteredValues)
      associativeAgg match {
        case dimensionsValuesTime: DStream[(DimensionValuesTime, AggregationsValues)] => {
          Option(updateAssociativeStateWithTime(dimensionsValuesTime))
        }
        case dimensionsValuesWithoutTime: DStream[(DimensionValuesWithoutTime, AggregationsValues)] => {
          Option(updateAssociativeStateWithoutTime(dimensionsValuesWithoutTime))
        }
      }
    }
    else None
    val nonAssociativesCalculated = if (nonAssociativeOperators.nonEmpty){
      filteredValues match {
        case dimensionsValuesTime: DStream[(DimensionValuesTime, InputFields)] => {
          Option(aggregateNonAssociativeValuesWithTime(updateNonAssociativeStateWithTime(dimensionsValuesTime)))
        }
        case dimensionsValuesWithoutTime: DStream[(DimensionValuesWithoutTime, InputFields)] => {
          Option(aggregateNonAssociativeValuesWithoutTime(updateNonAssociativeStateWithoutTime(dimensionsValuesWithoutTime)))
        }
      }
    }
    else None

    (associativesCalculated, nonAssociativesCalculated) match {
      case (Some(associativeValues:DStream[(DimensionValues, MeasuresValues)]),
      Some(nonAssociativeValues: DStream[(DimensionValues, MeasuresValues)])) =>
        associativeValues.cogroup(nonAssociativeValues)
          .mapValues { case (associativeAggregations, nonAssociativeAggregations) => MeasuresValues(
            (associativeAggregations.flatMap(_.values) ++ nonAssociativeAggregations.flatMap(_.values))
              .toMap)
          }
      case (Some(associativeValues:DStream[(DimensionValues, MeasuresValues)]), None) => associativeValues
      case (None, Some(nonAssociativeValues:DStream[(DimensionValues, MeasuresValues)])) => nonAssociativeValues
      case _ =>
        log.warn("You should define operators for aggregate input values")
        noAggregationsState(dimensionsValues)
    }
  }
  //scalastyle: on

  /**
   * Filter dimension values that correspond with the current cube dimensions
   */

  protected def filterDimensionValues(dimensionValues: DStream[(DimensionValues, InputFieldsValues)])
  : DStream[(DimensionValues, InputFields)] = {

    dimensionValues.map {
      case (dimensionsValuesTime: DimensionValuesTime, aggregationValues) =>
        val dimensionsFiltered = dimensionsValuesTime.dimensionValuesTime.filter(dimVal =>
          dimensions.exists(comp => comp.name == dimVal.dimension.name))

        (dimensionsValuesTime.copy(dimensionValuesTime = dimensionsFiltered, timeDimension = checkpointTimeDimension),
          InputFields(aggregationValues, UpdatedValues))

      case (dimensionsValuesWithoutTime: DimensionValuesWithoutTime, aggregationValues) =>
        val dimensionsFiltered = dimensionsValuesWithoutTime.dimensionValues.filter(dimVal =>
          dimensions.exists(comp => comp.name == dimVal.dimension.name))

        (dimensionsValuesWithoutTime.copy(dimensionValues = dimensionsFiltered),
          InputFields(aggregationValues, UpdatedValues))
    }
  }


  protected def updateNonAssociativeStateWithTime(dimensionsValues: DStream[(DimensionValuesTime, InputFields)])
  : DStream[(DimensionValuesTime, Seq[Aggregation])] = {

    dimensionsValues.checkpoint(new Duration(checkpointInterval))

    val newUpdateFunc = (iterator: Iterator[(DimensionValuesTime, Seq[InputFields], Option[AggregationsValues])]) => {

      val eventTime =
        DateOperations.dateFromGranularity(DateTime.now(), checkpointGranularity) - checkpointTimeAvailability

      iterator.filter { case (dimensionValueTime, _, _) => dimensionValueTime.time >= eventTime }
        .flatMap { case (dimensionsKey, values, state) =>
          updateNonAssociativeFunction(values, state).map(result => (dimensionsKey, result))
        }
    }
    val valuesCheckpointed = dimensionsValues.updateStateByKey(
      newUpdateFunc, new HashPartitioner(dimensionsValues.context.sparkContext.defaultParallelism), rememberPartitioner)

    filterUpdatedAggregationsValuesWithTime(valuesCheckpointed)
  }

  protected def updateNonAssociativeStateWithoutTime(
   dimensionsValues: DStream[(DimensionValuesWithoutTime, InputFields)])
  : DStream[(DimensionValuesWithoutTime, Seq[Aggregation])] = {

    dimensionsValues.checkpoint(new Duration(checkpointInterval))

    val newUpdateFunc = (
     iterator: Iterator[(DimensionValuesWithoutTime, Seq[InputFields], Option[AggregationsValues])]) => {

      iterator
        .flatMap { case (dimensionsKey, values, state) =>
          updateNonAssociativeFunction(values, state).map(result => (dimensionsKey, result))
        }
    }
    val valuesCheckpointed = dimensionsValues.updateStateByKey(
      newUpdateFunc, new HashPartitioner(dimensionsValues.context.sparkContext.defaultParallelism), rememberPartitioner)

    filterUpdatedAggregationsValuesWithoutTime(valuesCheckpointed)
  }

  protected def updateNonAssociativeFunction(values: Seq[InputFields], state: Option[AggregationsValues])
  : Option[AggregationsValues] = {

    val proccessMapValues = values.flatMap(aggregationsValues =>
      nonAssociativeOperators.map(op => Aggregation(op.key, op.processMap(aggregationsValues.fieldsValues))))
    val lastState = state match {
      case Some(measures) => measures.values
      case None => Seq.empty
    }
    val (aggregations, newValues) = getUpdatedAggregations(lastState ++ proccessMapValues, values.nonEmpty)

    Option(AggregationsValues(aggregations, newValues))
  }


  protected def aggregateNonAssociativeValuesWithTime(dimensionsValues: DStream[(DimensionValuesTime, Seq[Aggregation])])
  : DStream[(DimensionValuesTime, MeasuresValues)] =

    dimensionsValues.mapValues(aggregationValues => {
      val measures = aggregationValues.groupBy(aggregation => aggregation.name)
        .map { case (name, aggregations) =>
          (name, nonAssociativeOperatorsMap(name).processReduce(aggregations.map(aggregation => aggregation.value)))
        }
      MeasuresValues(measures)
    })

  protected def aggregateNonAssociativeValuesWithoutTime(dimensionsValues: DStream[(DimensionValuesWithoutTime, Seq[Aggregation])])
  : DStream[(DimensionValuesWithoutTime, MeasuresValues)] =

    dimensionsValues.mapValues(aggregationValues => {
      val measures = aggregationValues.groupBy(aggregation => aggregation.name)
        .map { case (name, aggregations) =>
          (name, nonAssociativeOperatorsMap(name).processReduce(aggregations.map(aggregation => aggregation.value)))
        }
      MeasuresValues(measures)
    })


  protected def updateAssociativeStateWithTime(dimensionsValues: DStream[(DimensionValuesTime, AggregationsValues)])
  : DStream[(DimensionValuesTime, MeasuresValues)] = {

    dimensionsValues.checkpoint(new Duration(checkpointInterval))

    val newUpdateFunc = (iterator: Iterator[(DimensionValuesTime, Seq[AggregationsValues], Option[Measures])]) => {

      val eventTime =
        DateOperations.dateFromGranularity(DateTime.now(), checkpointGranularity) - checkpointTimeAvailability

      iterator.filter { case (dimensionValueTime, _, _) => dimensionValueTime.time >= eventTime }
        .flatMap { case (dimensionsKey, values, state) =>
          updateAssociativeFunction(values, state).map(result => (dimensionsKey, result))
        }
    }

    val valuesCheckpointed = dimensionsValues.updateStateByKey(
      newUpdateFunc, new HashPartitioner(dimensionsValues.context.sparkContext.defaultParallelism), rememberPartitioner)

    filterUpdatedMeasuresWithTime(valuesCheckpointed)
  }


  protected def updateAssociativeStateWithoutTime(
    dimensionsValues: DStream[(DimensionValuesWithoutTime, AggregationsValues)])
  : DStream[(DimensionValuesWithoutTime, MeasuresValues)] = {

    dimensionsValues.checkpoint(new Duration(checkpointInterval))

    val newUpdateFunc = (
      iterator: Iterator[(DimensionValuesWithoutTime,
      Seq[AggregationsValues],
      Option[Measures])]) => {

       iterator
        .flatMap { case (dimensionsKey, values, state) =>
          updateAssociativeFunction(values, state).map(result => (dimensionsKey, result))
        }
    }

    val valuesCheckpointed = dimensionsValues.updateStateByKey(
      newUpdateFunc, new HashPartitioner(dimensionsValues.context.sparkContext.defaultParallelism), rememberPartitioner)

    filterUpdatedMeasuresWithoutTime(valuesCheckpointed)
  }



  def associativeAggregation(dimensionsValues: DStream[(DimensionValues, InputFields)])
  : DStream[(DimensionValues, AggregationsValues)] =
    dimensionsValues.mapValues(inputFieldsValues =>
      associativeOperators.map(op => op.key -> op.processMap(inputFieldsValues.fieldsValues)))
      .groupByKey()
      .mapValues(aggregations => {
        val aggregatedValues = aggregations.flatMap(aggregationsMap => aggregationsMap)
          .groupBy { case (opKey, _) => opKey }
          .map { case (nameOp, valuesOp) =>
            val op = associativeOperatorsMap(nameOp)
            val values = valuesOp.map { case (_, value) => value }

            Aggregation(nameOp, op.processReduce(values))
          }.toSeq

        AggregationsValues(aggregatedValues, UpdatedValues)
      })


  //scalastyle:off
  protected def updateAssociativeFunction(values: Seq[AggregationsValues], state: Option[Measures])
  : Option[Measures] = {

    val stateWithoutUpdateVar = state match {
      case Some(measures) => measures.measuresValues.values
      case None => Map.empty
    }
    val actualState = stateWithoutUpdateVar.toSeq.map { case (key, value) => (key, (Operator.OldValuesKey, value)) }
    val newWithoutUpdateVar = values.map(aggregationsValues => aggregationsValues.values)
    val newValues = newWithoutUpdateVar.flatten.map(aggregation =>
      (aggregation.name, (Operator.NewValuesKey, aggregation.value)))
    val processAssociative = (newValues ++ actualState)
      .groupBy { case (key, _) => key }
      .map { case (opKey, opValues) =>
        associativeOperatorsMap(opKey) match {
          case op: Associative => (opKey, op.associativity(opValues.map { case (nameOp, valuesOp) => valuesOp }))
          case _ => (opKey, None)
        }
      }
    val (measuresValues, isNewMeasure) =
      getUpdatedAggregations(MeasuresValues(processAssociative), values.nonEmpty)

    Option(Measures(measuresValues, isNewMeasure))
  }

  //scalastyle:on

  protected def noAggregationsState(dimensionsValues: DStream[(DimensionValues, InputFieldsValues)])
  : DStream[(DimensionValues, MeasuresValues)] =
    dimensionsValues.mapValues(aggregations =>
      MeasuresValues(operators.map(op => op.key -> None).toMap))

  /**
   * Filter measuresValues that are been changed in this window
   */

  protected def filterUpdatedMeasuresWithTime(values: DStream[(DimensionValuesTime, Measures)])
  : DStream[(DimensionValuesTime, MeasuresValues)] =
    values.flatMapValues(measures => if (measures.newValues == UpdatedValues) Some(measures.measuresValues) else None)



  /**
    * Filter measuresValues that are been changed in this window
    */

  protected def filterUpdatedMeasuresWithoutTime(values: DStream[(DimensionValuesWithoutTime, Measures)])
  : DStream[(DimensionValuesWithoutTime, MeasuresValues)] =
    values.flatMapValues(measures => if (measures.newValues == UpdatedValues) Some(measures.measuresValues) else None)


  /**
   * Filter aggregationsValues that are been changed in this window
   */

  protected def filterUpdatedAggregationsValuesWithTime(values: DStream[(DimensionValuesTime, AggregationsValues)])
  : DStream[(DimensionValuesTime, Seq[Aggregation])] =
    values.flatMapValues(aggregationsValues => {
      if (aggregationsValues.newValues == UpdatedValues) Some(aggregationsValues.values) else None
    })


  /**
    * Filter aggregationsValues that are been changed in this window
    */

  protected def filterUpdatedAggregationsValuesWithoutTime(
    values: DStream[(DimensionValuesWithoutTime, AggregationsValues)])
  : DStream[(DimensionValuesWithoutTime, Seq[Aggregation])] =
    values.flatMapValues(aggregationsValues => {
      if (aggregationsValues.newValues == UpdatedValues) Some(aggregationsValues.values) else None
    })
  /**
   * Return the aggregations with the correct key in case of the actual streaming window have new values for the
   * dimensions values.
   */

  protected def getUpdatedAggregations[T](aggregations: T, haveNewValues: Boolean): (T, Int) =
    if (haveNewValues)
      (aggregations, UpdatedValues)
    else (aggregations, NotUpdatedValues)
}