// Copyright (C) 2014  Open Data ("Open Data" refers to
// one or more of the following companies: Open Data Partners LLC,
// Open Data Research LLC, or Open Data Capital LLC.)
// 
// This file is part of Hadrian.
// 
// Licensed under the Hadrian Personal Use and Evaluation License (PUEL);
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// 
//     http://raw.githubusercontent.com/opendatagroup/hadrian/master/LICENSE
// 
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.opendatagroup.hadrian.lib1.stat

import scala.annotation.tailrec
import scala.collection.immutable.ListMap
import scala.language.postfixOps

import org.apache.avro.Schema

import com.opendatagroup.hadrian.ast.LibFcn
import com.opendatagroup.hadrian.errors.PFASemanticException
import com.opendatagroup.hadrian.errors.PFARuntimeException
import com.opendatagroup.hadrian.jvmcompiler.JavaCode
import com.opendatagroup.hadrian.jvmcompiler.javaSchema
import com.opendatagroup.hadrian.jvmcompiler.PFAEngineBase

import com.opendatagroup.hadrian.ast.AstContext
import com.opendatagroup.hadrian.ast.ExpressionContext
import com.opendatagroup.hadrian.ast.FcnDef
import com.opendatagroup.hadrian.ast.FcnRef

import com.opendatagroup.hadrian.data.PFAArray
import com.opendatagroup.hadrian.data.PFAMap
import com.opendatagroup.hadrian.data.PFARecord

import com.opendatagroup.hadrian.signature.P
import com.opendatagroup.hadrian.signature.Sig
import com.opendatagroup.hadrian.signature.Signature
import com.opendatagroup.hadrian.signature.Sigs

import com.opendatagroup.hadrian.datatype.Type
import com.opendatagroup.hadrian.datatype.FcnType
import com.opendatagroup.hadrian.datatype.AvroType
import com.opendatagroup.hadrian.datatype.AvroNull
import com.opendatagroup.hadrian.datatype.AvroBoolean
import com.opendatagroup.hadrian.datatype.AvroInt
import com.opendatagroup.hadrian.datatype.AvroLong
import com.opendatagroup.hadrian.datatype.AvroFloat
import com.opendatagroup.hadrian.datatype.AvroDouble
import com.opendatagroup.hadrian.datatype.AvroBytes
import com.opendatagroup.hadrian.datatype.AvroFixed
import com.opendatagroup.hadrian.datatype.AvroString
import com.opendatagroup.hadrian.datatype.AvroEnum
import com.opendatagroup.hadrian.datatype.AvroArray
import com.opendatagroup.hadrian.datatype.AvroMap
import com.opendatagroup.hadrian.datatype.AvroRecord
import com.opendatagroup.hadrian.datatype.AvroField
import com.opendatagroup.hadrian.datatype.AvroUnion

package object sample {
  private var fcns = Map[String, LibFcn]()
  def provides = fcns
  def provide(libFcn: LibFcn): Unit =
    fcns = fcns + Tuple2(libFcn.name, libFcn)

  val prefix = "stat.sample."

  //////////////////////////////////////////////////////////////////// 

  ////   update (Update)
  object Update extends LibFcn {
    val name = prefix + "update"
    val sig = Sig(List("x" -> P.Double, "w" -> P.Double, "state" -> P.WildRecord("A", ListMap("count" -> P.Double))), P.Wildcard("A"))
    val doc =
      <doc>
        <desc>Update the state of a counter, a counter and a mean, or a counter, mean, and variance.</desc>
        <param name="x">Sample value.</param>
        <param name="w">Sample weight; set to 1 for no weights.</param>
        <param name="state">Record of the previous <pf>count</pf>, <pf>mean</pf>, and/or <pf>variance</pf>.
          <paramField name="count">The sum of weights <p>w</p>.</paramField>
          <paramField name="mean">The mean of <p>x</p>, weighted by <p>w</p>.  This field is optional, but if provided, it must be a <c>double</c>.</paramField>
          <paramField name="variance">The variance of <m>{"""x - \mbox{mean}"""}</m>, weighted by <p>w</p>.  This field is optional, but if it is provided, it must be a <c>double</c>, and there must be a <pf>mean</pf> as well.  No attempt is made to unbias the estimator, so multiply this by <m>{"""\mbox{count}/(\mbox{count} - 1)"""}</m> to correct for the bias due to centering on the mean.</paramField>
        </param>
        <ret>Returns an updated version of <p>state</p> with <pf>count</pf> incremented by <p>w</p>, <pf>mean</pf> updated to the current mean of all <p>x</p>, and <pf>variance</pf> updated to the current variance of all <p>x</p>.  If the <p>state</p> has fields other than <pf>count</pf>, <pf>mean</pf>, and <pf>variance</pf>, they are copied unaltered to the output state.</ret>
    </doc>

    override def javaCode(args: Seq[JavaCode], argContext: Seq[AstContext], paramTypes: Seq[Type], retType: AvroType): JavaCode = {
      val record = retType.asInstanceOf[AvroRecord]

      val hasMean = record.fieldOption("mean") match {
        case None => false
        case Some(field) => {
          if (!field.avroType.accepts(AvroDouble()))
            throw new PFASemanticException(prefix + "update is being given a state record type in which the \"mean\" field is not a double: " + field.avroType.toString, None)
          true
        }
      }

      val hasVariance = record.fieldOption("variance") match {
        case None => false
        case Some(field) => {
          if (!field.avroType.accepts(AvroDouble()))
            throw new PFASemanticException(prefix + "update is being given a state record type in which the \"variance\" field is not a double: " + field.avroType.toString, None)
          true
        }
      }

      val level = (hasMean, hasVariance) match {
        case (false, false) => 0
        case (true, false) => 1
        case (true, true) => 2
        case (false, true) => throw new PFASemanticException(prefix + "update with \"variance\" must also have \"mean\" in the state record type", None)
      }

      JavaCode("%s.MODULE$.apply(%s, %s, %s, %s)",
        this.getClass.getName,
        wrapArg(0, args, paramTypes, true),
        wrapArg(1, args, paramTypes, true),
        wrapArg(2, args, paramTypes, true),
        level.toString)
    }

    def apply(x: Double, w: Double, state: AnyRef, level: Int): PFARecord = {
      val record = state.asInstanceOf[PFARecord]
      val originalCount = record.get("count").asInstanceOf[java.lang.Number].doubleValue
      val count = originalCount + w
      if (level == 0)
        record.multiUpdate(Array("count"), Array(count))
      else {
        var mean = record.get("mean").asInstanceOf[java.lang.Number].doubleValue
        val delta = x - mean
        val shift = delta * w / count
        mean += shift
        if (level == 1)
          record.multiUpdate(Array("count", "mean"), Array(count, mean))
        else {
          var varianceTimesCount = record.get("variance").asInstanceOf[java.lang.Number].doubleValue * originalCount
          varianceTimesCount += originalCount * delta * shift
          record.multiUpdate(Array("count", "mean", "variance"), Array(count, mean, varianceTimesCount / count))
        }
      }
    }
  }
  provide(Update)

  ////   updateCovariance (UpdateCovariance)
  object UpdateCovariance extends LibFcn {
    val name = prefix + "updateCovariance"
    val sig = Sigs(List(Sig(List("x" -> P.Array(P.Double), "w" -> P.Double, "state" -> P.WildRecord("A", ListMap("count" -> P.Double, "mean" -> P.Array(P.Double), "covariance" -> P.Array(P.Array(P.Double))))), P.Wildcard("A")),
                        Sig(List("x" -> P.Map(P.Double), "w" -> P.Double, "state" -> P.WildRecord("A", ListMap("count" -> P.Map(P.Map(P.Double)), "mean" -> P.Map(P.Double), "covariance" -> P.Map(P.Map(P.Double))))), P.Wildcard("A"))))
    val doc =
      <doc>
        <desc>Update the state of a covariance calculation.</desc>
        <param name="x">Sample vector, expressed as an array or map; must have at least two components.</param>
        <param name="w">Sample weight; set to 1 for no weights.</param>
        <param name="state">Record of the previous <pf>count</pf>, <pf>mean</pf>, and <pf>covariance</pf>.
          <paramField name="count">The sum of weights <p>w</p>.  If <p>x</p> is an array, then <pf>count</pf> is a single value representing the sum of weights for all records seen so far.  If <p>x</p> is a map, then <pf>count</pf> is a matrix in which entry <m>i</m>, <m>j</m> is the sum of weights for records in which key <m>i</m> and key <m>j</m> both appear in <p>x</p>.</paramField>
          <paramField name="mean">The componentwise mean of <p>x</p>, weighted by <p>w</p>.</paramField>
          <paramField name="covariance">The covariance matrix of all pairs of components of <p>x</p>, weighted by <p>w</p>.  If <p>x</p> is an array, this matrix is represented by a list of lists.  If <p>x</p> is a map, this matrix is represented by a map of maps.</paramField>
        </param>
        <ret>Returns an updated version of <p>state</p> with <pf>count</pf> incremented by <p>w</p>, <pf>mean</pf> updated to the current componentwise mean of all <p>x</p>, and <pf>covariance</pf> updated to the current covariance matrix of all <p>x</p>.  If the <p>state</p> has fields other than <pf>count</pf>, <pf>mean</pf>, and <pf>covariance</pf>, they are copied unaltered to the output state.</ret>
        <detail>Like most functions that deal with matrices, this function has an array signature and a map signature.  In the array signature, indexes of <p>x</p> correspond to the same indexes of <pf>mean</pf> and rows and columns of <pf>covariance</pf>, where a row is an index of <pf>covariance</pf> and a column is an index of an element of <pf>covariance</pf>.  In the map signature, keys of <p>x</p> correspond to the same keys of <pf>mean</pf>, as well as rows and columns of <pf>count</pf> and <pf>covariance</pf>, where a row is a key of the object and a column is a key of a value of the object.  In the array signature, all arrays must have equal length (including the nested arrays within <pf>covariance</pf>) and all components are updated with each call.  In the map signature, a previously unseen key in <p>x</p> creates a new key in <pf>mean</pf> with value <p>x</p>, a new row and column in <pf>count</pf> with value <p>w</p> for all key pairs existing in <p>x</p> and zero for key pairs not in <p>x</p>, as well as a new row and column in <pf>covariance</pf> filled with zeros.</detail>
        <detail>In the map signature, missing keys in <p>x</p> are equivalent to contributions with zero weight.</detail>
        <error>If <p>state</p> is <c>null</c> and the record type has fields other than <pf>count</pf>, <pf>mean</pf>, and <pf>covariance</pf>, then a "cannot initialize unrecognized fields" error is raised.  Unrecognized fields are only allowed if an initial record is provided.</error>
        <error>If <p>x</p> has fewer than 2 components, a "too few components" error is raised.</error>
        <error>If <p>x</p>, <pf>mean</pf>, and <pf>covariance</pf> are arrays with unequal lengths, an "unequal length arrays" error is raised.</error>
      </doc>

    def apply(x: PFAArray[Double], w: Double, state: PFARecord): PFARecord = {
      val xvector = x.toVector
      val size = xvector.size
      if (size < 2)
        throw new PFARuntimeException("too few components")

      val record = state.asInstanceOf[PFARecord]
      val oldCount = record.get("count").asInstanceOf[Double]
      val oldMean = record.get("mean").asInstanceOf[PFAArray[Double]].toVector
      val oldCovariance = record.get("covariance").asInstanceOf[PFAArray[PFAArray[Double]]].toVector map {_.toVector}

      if ((size != oldMean.size)  ||  (size != oldCovariance.size)  ||  (oldCovariance exists {size != _.size}))
        throw new PFARuntimeException("unequal length arrays")

      val newCount = oldCount + w
      val newMean = PFAArray.fromVector(oldMean.zipWithIndex map {case (oldm, i) => oldm + ((x(i) - oldm) * w / newCount)})
      val newCovariance =
        PFAArray.fromVector(
          (for (i <- 0 until size) yield
            PFAArray.fromVector(
              (for (j <- 0 until size) yield
                ((oldCovariance(i)(j)*oldCount) + ((x(i) - oldMean(i)) * (x(j) - oldMean(j)) * w*oldCount/newCount)) / newCount).toVector)).toVector)

      record.multiUpdate(Array("count", "mean", "covariance"), Array(newCount, newMean, newCovariance))
    }

    def apply(x: PFAMap[java.lang.Double], w: Double, state: PFARecord): PFARecord = {
      val xmap = x.toMap map {case (k, v) => (k, v.doubleValue)}
      val xkeys = xmap.keySet
      if (xkeys.size < 2)
        throw new PFARuntimeException("too few components")

      val record = state.asInstanceOf[PFARecord]
      val oldCount: Map[String, Map[String, Double]] =
        record.get("count").asInstanceOf[PFAMap[PFAMap[java.lang.Double]]].toMap map {case (k, v) => (k, v.toMap map {case (kk, vv) => (kk, vv.doubleValue)})}
      val oldMean: Map[String, Double] =
        record.get("mean").asInstanceOf[PFAMap[java.lang.Double]].toMap map {case (k, v) => (k, v.doubleValue)}
      val oldCovariance: Map[String, Map[String, Double]] =
        record.get("covariance").asInstanceOf[PFAMap[PFAMap[java.lang.Double]]].toMap map {case (k, v) => (k, v.toMap map {case (kk, vv) => (kk, vv.doubleValue)})}

      val countKeys = oldCount.keySet union (if (oldCount.isEmpty) Set[String]() else (oldCount.values map {_.keySet} reduce {_ union _}))
      val covarKeys = oldCovariance.keySet union (if (oldCovariance.isEmpty) Set[String]() else (oldCovariance.values map {_.keySet} reduce {_ union _}))
      val keys = xkeys union countKeys union covarKeys

      val newCount: Map[String, Map[String, Double]] =
        (for (i <- keys) yield
          (i, (for (j <- keys) yield {
            val old = oldCount.getOrElse(i, Map[String, Double]()).getOrElse(j, 0.0)
            if ((xkeys contains i)  &&  (xkeys contains j))
              (j, old + w)
            else
              (j, old)
          }).toMap)).toMap

      val newMean: Map[String, Double] =
        (for (i <- keys) yield {
          val old = oldMean.getOrElse(i, 0.0)
          if (xkeys contains i)
            (i, old + ((xmap(i) - old) * w / newCount(i)(i)))
          else
            (i, old)
        }).toMap

      val newCovariance: Map[String, Map[String, Double]] =
        (for (i <- keys) yield
          (i, (for (j <- keys) yield {
            val oldCov = oldCovariance.getOrElse(i, Map[String, Double]()).getOrElse(j, 0.0)
            if ((xkeys contains i)  &&  (xkeys contains j)) {
              val oldC = oldCount.getOrElse(i, Map[String, Double]()).getOrElse(j, 0.0)
              val oldMi = oldMean.getOrElse(i, 0.0)
              val oldMj = oldMean.getOrElse(j, 0.0)
              (j, ((oldCov*oldC) + ((xmap(i) - oldMi) * (xmap(j) - oldMj) * w*oldC/newCount(i)(j))) / newCount(i)(j))
            }
            else
              (j, oldCov)
          }).toMap)).toMap

      record.multiUpdate(Array("count", "mean", "covariance"), Array(
        PFAMap.fromMap(newCount map {case (k, v) => (k, PFAMap.fromMap(v map {case (kk, vv) => (kk, java.lang.Double.valueOf(vv))}))}),
        PFAMap.fromMap(newMean map {case (k, v) => (k, java.lang.Double.valueOf(v))}),
        PFAMap.fromMap(newCovariance map {case (k, v) => (k, PFAMap.fromMap(v map {case (kk, vv) => (kk, java.lang.Double.valueOf(vv))}))})))
    }
  }
  provide(UpdateCovariance)

  ////   updateWindow (UpdateWindow)
  object UpdateWindow extends LibFcn {
    val name = prefix + "updateWindow"
    val sig = Sig(List("x" -> P.Double, "w" -> P.Double, "state" -> P.Array(P.WildRecord("A", ListMap("x" -> P.Double, "w" -> P.Double, "count" -> P.Double))), "windowSize" -> P.Int), P.Array(P.Wildcard("A")))
    val doc =
      <doc>
        <desc>Update the state of a counter, a counter and a mean, or a counter, mean, and variance, within a window of <p>windowSize</p> recent samples.</desc>
        <param name="x">Sample value.</param>
        <param name="w">Sample weight; set to 1 for no weights.</param>
        <param name="state">Array of previous <pf>count</pf>, <pf>mean</pf>, and/or <pf>variance</pf> and samples in the window.
          <paramField name="count">The sum of weights <p>w</p> within the window.</paramField>
          <paramField name="mean">The mean of <p>x</p> within the window, weighted by <p>w</p>.  This field is optional, but if provided, it must be a <c>double</c>.</paramField>
          <paramField name="variance">The variance of <m>{"""x - \mbox{mean}"""}</m> within the window, weighted by <p>w</p>.  This field is optional, but if it is provided, it must be a <c>double</c>, and there must be a <pf>mean</pf> as well.  No attempt is made to unbias the estimator, so multiply this by <m>{"""\mbox{count}/(\mbox{count} - 1)"""}</m> to correct for the bias due to centering on the mean.</paramField>
          <paramField name="x">Sample value, saved so that it can be removed from the running mean and variance when it goes out of scope.</paramField>
          <paramField name="w">Sample weight, saved for the same reason.</paramField>
        </param>
        <param name="windowSize">Size of the window.  When the length of <p>state</p> is less than <p>windowSize</p>, this function is equivalent to <f>{prefix + "update"}</f>.</param>
        <ret>If the length of <p>state</p> is zero, this function returns a singleton array with <pf>count</pf> = <p>w</p>, <pf>mean</pf> = <p>x</p>, and/or <pf>variance</pf> = 0.  If the length of <p>state</p> is less than <p>windowSize</p>, then it returns a copy of <p>state</p> with the next record added.  Otherwise, it is trunctated to <p>windowSize</p>, removing the old values from the running count/mean/variance.  In all cases, the <f>a.last</f> item is the latest result.</ret>
        <error>If <p>windowSize</p> is less than 2, a "windowSize must be at least 2" error is raised.</error>
        <error>If <p>state</p> is empty and the record type has fields other than <pf>count</pf>, <pf>mean</pf>, and <pf>variance</pf>, then a "cannot initialize unrecognized fields" error is raised.  Unrecognized fields are only allowed if an initial record is provided.</error>
    </doc>

    override def javaCode(args: Seq[JavaCode], argContext: Seq[AstContext], paramTypes: Seq[Type], retType: AvroType): JavaCode = {
      val record = retType.asInstanceOf[AvroArray].items.asInstanceOf[AvroRecord]

      val hasMean = record.fieldOption("mean") match {
        case None => false
        case Some(field) => {
          if (!field.avroType.accepts(AvroDouble()))
            throw new PFASemanticException(prefix + "updateWindow is being given a state record type in which the \"mean\" field is not a double: " + field.avroType.toString, None)
          true
        }
      }

      val hasVariance = record.fieldOption("variance") match {
        case None => false
        case Some(field) => {
          if (!field.avroType.accepts(AvroDouble()))
            throw new PFASemanticException(prefix + "updateWindow is being given a state record type in which the \"variance\" field is not a double: " + field.avroType.toString, None)
          true
        }
      }

      val level = (hasMean, hasVariance) match {
        case (false, false) => 0
        case (true, false) => 1
        case (true, true) => 2
        case (false, true) => throw new PFASemanticException(prefix + "updateWindow with \"variance\" must also have \"mean\" in the state record type", None)
      }

      val hasOthers = !(record.fields.map(_.name).toSet subsetOf Set("x", "w", "count", "mean", "variance"))

      JavaCode("%s.MODULE$.apply(%s, %s, %s, %s, thisEngineBase, %s, %s, %s)",
        this.getClass.getName,
        wrapArg(0, args, paramTypes, true),
        wrapArg(1, args, paramTypes, true),
        wrapArg(2, args, paramTypes, true),
        wrapArg(3, args, paramTypes, true),
        javaSchema(retType, false),
        level.toString,
        (if (hasOthers) "true" else "false"))
    }

    def apply(x: Double, w: Double, state: PFAArray[PFARecord], windowSize: Int, pfaEngineBase: PFAEngineBase, schema: Schema, level: Int, hasOthers: Boolean): PFAArray[PFARecord] = {
      var vector = state.toVector

      if (windowSize < 2)
        throw new PFARuntimeException("windowSize must be at least 2")

      if (vector.isEmpty) {
        if (hasOthers)
          throw new PFARuntimeException("cannot initialize unrecognized fields")
        level match {
          case 0 => pfaEngineBase.fromJson("""[{"x": %s, "w": %s, "count": %s}]""".format(x, w, w), schema).asInstanceOf[PFAArray[PFARecord]]
          case 1 => pfaEngineBase.fromJson("""[{"x": %s, "w": %s, "count": %s, "mean": %s}]""".format(x, w, w, x), schema).asInstanceOf[PFAArray[PFARecord]]
          case 2 => pfaEngineBase.fromJson("""[{"x": %s, "w": %s, "count": %s, "mean": %s, "variance": 0.0}]""".format(x, w, w, x), schema).asInstanceOf[PFAArray[PFARecord]]
        }
      }

      else {
        val record = vector.last

        if (vector.size >= windowSize) {
          val (remove, keep) = vector.splitAt(vector.size - windowSize + 1)
          val oldx = remove map {_.get("x").asInstanceOf[java.lang.Number].doubleValue}
          val oldw = remove map {_.get("w").asInstanceOf[java.lang.Number].doubleValue * -1.0}

          val originalCount = record.get("count").asInstanceOf[java.lang.Number].doubleValue
          val count = originalCount + w

          val count2 = count + oldw.sum

          if (level == 0)
            PFAArray.fromVector(keep :+ record.multiUpdate(Array("x", "w", "count"), Array(x, w, count2)))

          else {
            var mean = record.get("mean").asInstanceOf[java.lang.Number].doubleValue
            val delta = x - mean
            val shift = delta * w / count

            mean += shift

            var accumulatedCount = count
            var varianceCorrection = 0.0
            for ((ox, ow) <- oldx zip oldw) {
              accumulatedCount += ow
              val delta2 = ox - mean
              val shift2 = delta2 * ow / accumulatedCount

              mean += shift2
              varianceCorrection += (accumulatedCount - ow) * delta2 * shift2
            }

            if (level == 1)
              PFAArray.fromVector(keep :+ record.multiUpdate(Array("x", "w", "count", "mean"), Array(x, w, count2, mean)))

            else {
              var varianceTimesCount = record.get("variance").asInstanceOf[java.lang.Number].doubleValue * originalCount
              varianceTimesCount += originalCount * delta * shift

              varianceTimesCount += varianceCorrection

              PFAArray.fromVector(keep :+ record.multiUpdate(Array("x", "w", "count", "mean", "variance"), Array(x, w, count2, mean, varianceTimesCount / count2)))
            }
          }

        }

        else {
          val originalCount = record.get("count").asInstanceOf[java.lang.Number].doubleValue
          val count = originalCount + w

          if (level == 0)
            PFAArray.fromVector(vector :+ record.multiUpdate(Array("x", "w", "count"), Array(x, w, count)))

          else {
            var mean = record.get("mean").asInstanceOf[java.lang.Number].doubleValue
            val delta = x - mean
            val shift = delta * w / count
            mean += shift

            if (level == 1)
              PFAArray.fromVector(vector :+ record.multiUpdate(Array("x", "w", "count", "mean"), Array(x, w, count, mean)))

            else {
              var varianceTimesCount = record.get("variance").asInstanceOf[java.lang.Number].doubleValue * originalCount
              varianceTimesCount += originalCount * delta * shift
              PFAArray.fromVector(vector :+ record.multiUpdate(Array("x", "w", "count", "mean", "variance"), Array(x, w, count, mean, varianceTimesCount / count)))
            }
          }
        }

      }
    }
  }
  provide(UpdateWindow)

  ////   updateEWMA (UpdateEWMA)
  object UpdateEWMA extends LibFcn {
    val name = prefix + "updateEWMA"
    val sig = Sig(List("x" -> P.Double, "alpha" -> P.Double, "state" -> P.Union(List(P.Null, P.WildRecord("A", ListMap("mean" -> P.Double))))), P.Wildcard("A"))
    val doc =
      <doc>
        <desc>Update the state of an exponentially weighted moving average (EWMA).</desc>
        <param name="x">Sample value.</param>
        <param name="alpha">Weighting factor (usually a constant) between 0 and 1, inclusive.  If <p>alpha</p> is close to 1, recent data are heavily weighted at the expense of old data; if <p>alpha</p> is close to 0, the EWMA approaches a simple mean.</param>
        <param name="state">Record of the previous <pf>mean</pf> and <pf>variance</pf>, which may be <c>null</c> to start a new sample.
          <paramField name="mean">The exponentially weighted mean of <p>x</p>, weighted by <p>alpha</p>.</paramField>
          <paramField name="variance">The exponentially weighted variance of <p>x</p>, weighted by <p>alpha</p>.  This field is optional, but if provided, it must be a <c>double</c>.</paramField>
        </param>
        <ret>If <p>state</p> is <c>null</c>, a new record is created with <pf>mean</pf> = <p>x</p>, <pf>variance</pf> = 0.  If <p>state</p> is a record, then this function returns an updated version of that record.  If the input <p>state</p> has fields other than <pf>mean</pf> and <pf>variance</pf>, they are copied unaltered to the output state.</ret>
        <error>If <p>alpha</p> is less than 0 or greater than 1, an "alpha out of range" error is raised.</error>
        <error>If <p>state</p> is <c>null</c> and the record type has fields other than <pf>mean</pf> and <pf>variance</pf>, then a "cannot initialize unrecognized fields" error is raised.  Unrecognized fields are only allowed if an initial record is provided.</error>
      </doc>

    override def javaCode(args: Seq[JavaCode], argContext: Seq[AstContext], paramTypes: Seq[Type], retType: AvroType): JavaCode = {
      val record = retType.asInstanceOf[AvroRecord]

      val hasVariance = record.fieldOption("variance") match {
        case None => false
        case Some(field) => {
          if (!field.avroType.accepts(AvroDouble()))
            throw new PFASemanticException(prefix + "updateEWMA is being given a state record type in which the \"variance\" field is not a double: " + field.avroType.toString, None)
          true
        }
      }

      val hasOthers = !(record.fields.map(_.name).toSet subsetOf Set("mean", "variance"))

      JavaCode("%s.MODULE$.apply(%s, %s, %s, thisEngineBase, %s, %s, %s)",
        this.getClass.getName,
        wrapArg(0, args, paramTypes, true),
        wrapArg(1, args, paramTypes, true),
        wrapArg(2, args, paramTypes, true),
        javaSchema(retType, false),
        (if (hasVariance) "true" else "false"),
        (if (hasOthers) "true" else "false"))
    }

    def apply(x: Double, alpha: Double, state: AnyRef, pfaEngineBase: PFAEngineBase, schema: Schema, hasVariance: Boolean, hasOthers: Boolean): PFARecord = {
      if (alpha < 0.0  ||  alpha > 1.0)
        throw new PFARuntimeException("alpha out of range")

      if (state == null) {
        if (hasOthers)
          throw new PFARuntimeException("cannot initialize unrecognized fields")
        if (hasVariance)
          pfaEngineBase.fromJson("""{"mean": %s, "variance": 0.0}""".format(x), schema).asInstanceOf[PFARecord]
        else
          pfaEngineBase.fromJson("""{"mean": %s}""".format(x), schema).asInstanceOf[PFARecord]
      }
      else {
        val record = state.asInstanceOf[PFARecord]

        val mean = record.get("mean").asInstanceOf[java.lang.Number].doubleValue
        val diff = x - mean
        val incr = alpha * diff

        if (hasVariance) {
          val variance = record.get("variance").asInstanceOf[java.lang.Number].doubleValue
          record.multiUpdate(Array("mean", "variance"), Array(mean + incr, (1.0 - alpha) * (variance + diff * incr)))
        }
        else
          record.multiUpdate(Array("mean"), Array(mean + incr))
      }
    }
  }
  provide(UpdateEWMA)

  ////   updateHoltWinters (UpdateHoltWinters)
  object UpdateHoltWinters extends LibFcn {
    val name = prefix + "updateHoltWinters"
    val sig = Sig(List("x" -> P.Double, "alpha" -> P.Double, "beta" -> P.Double, "state" -> P.WildRecord("A", ListMap("level" -> P.Double, "trend" -> P.Double))), P.Wildcard("A"))
    val doc =
      <doc>
        <desc>Update the state of a time series analysis with an exponentially weighted linear fit.</desc>
        <param name="x">Sample value.</param>
        <param name="alpha">Weighting factor (usually a constant) between 0 and 1, inclusive, that governs the responsiveness of the <pf>level</pf>.  If <p>alpha</p> is close to 1, recent data are heavily weighted at the expense of old data.</param>
        <param name="beta">Weighting factor (usually a constant) between 0 and 1, inclusive, that governs the responsiveness of the <pf>trend</pf>.  If <p>beta</p> is close to 1, recent data are heavily weighted at the expense of old data.</param>
        <param name="state">Record of the previous <pf>level</pf> and <pf>trend</pf>.
          <paramField name="level">The constant term in an exponentially weighted linear fit of recent data, weighted by <p>alpha</p>.</paramField>
          <paramField name="trend">The linear term in an exponentially weighted linear fit of recent data, weighted by <p>beta</p>.</paramField>
        </param>
        <ret>Returns an updated version of the <p>state</p>.</ret>
        <detail>Use <f>stat.sample.forecast1HoltWinters</f> or <f>stat.sample.forecastHoltWinters</f> to make predictions from the state record.</detail>
        <detail>For <m>{"a_t"}</m> = the <pf>level</pf> at a time <m>{"t"}</m> and <m>{"b_t"}</m> = the <pf>trend</pf> at a time <m>{"t"}</m>, <m>{"""a_t = \alpha x + (1 - \alpha)(a_{t-1} + b_{t-1})"""}</m> and <m>{"""b_t = \beta (a_t - a_{t-1}) + (1 - \beta) b_{t-1}"""}</m>.</detail>
        <error>If <p>alpha</p> is less than 0 or greater than 1, an "alpha out of range" error is raised.</error>
        <error>If <p>beta</p> is less than 0 or greater than 1, an "beta out of range" error is raised.</error>
      </doc>
    def apply(x: Double, alpha: Double, beta: Double, record: PFARecord): PFARecord = {
      if (alpha < 0.0  ||  alpha > 1.0)
        throw new PFARuntimeException("alpha out of range")
      if (beta < 0.0  ||  beta > 1.0)
        throw new PFARuntimeException("beta out of range")

      val level_prev = record.get("level").asInstanceOf[java.lang.Number].doubleValue
      val trend_prev = record.get("trend").asInstanceOf[java.lang.Number].doubleValue

      val level = alpha * x + (1.0 - alpha) * (level_prev + trend_prev)
      val trend = beta * (level - level_prev) + (1.0 - beta) * trend_prev

      record.multiUpdate(Array("level", "trend"), Array(level, trend))
    }
  }
  provide(UpdateHoltWinters)

  ////   updateHoltWintersPeriodic (UpdateHoltWintersPeriodic)
  object UpdateHoltWintersPeriodic extends LibFcn {
    val name = prefix + "updateHoltWintersPeriodic"
    val sig = Sig(List("x" -> P.Double, "alpha" -> P.Double, "beta" -> P.Double, "gamma" -> P.Double, "state" -> P.Union(List(P.Null, P.WildRecord("A", ListMap("level" -> P.Double, "trend" -> P.Double, "cycle" -> P.Array(P.Double), "multiplicative" -> P.Boolean))))), P.Wildcard("A"))
    val doc =
      <doc>
        <desc>Update the state of a time series analysis with an exponentially weighted periodic-plus-linear fit.</desc>
        <param name="x">Sample value.</param>
        <param name="alpha">Weighting factor (usually a constant) between 0 and 1, inclusive, that governs the responsiveness of the <pf>level</pf>.  If <p>alpha</p> is close to 1, recent data are heavily weighted at the expense of old data.</param>
        <param name="beta">Weighting factor (usually a constant) between 0 and 1, inclusive, that governs the responsiveness of the <pf>trend</pf>.  If <p>beta</p> is close to 1, recent data are heavily weighted at the expense of old data.</param>
        <param name="gamma">Weighting factor (usually a constant) between 0 and 1, inclusive, that governs the responsiveness of the <pf>cycle</pf>.  If <p>gamma</p> is close to 1, recent data are heavily weighted at the expense of old data.</param>
        <param name="state">Record of the previous <pf>level</pf>, <pf>trend</pf>, and <pf>cycle</pf>.
          <paramField name="level">The constant term in an exponentially weighted linear fit of recent data, weighted by <p>alpha</p>.</paramField>
          <paramField name="trend">The linear term in an exponentially weighted linear fit of recent data, weighted by <p>beta</p>.</paramField>
          <paramField name="cycle">The history of the previous cycle, weighted by <p>gamma</p>.  If the length of this array is <m>L</m>, then the built-in period is <m>L</m> time steps long.</paramField>
          <paramField name="multiplicative">If <c>true</c>, interpret <pf>cycle</pf> as multiplicative; if <c>false</c>, interpret it as additive.</paramField>
        </param>
        <ret>Returns an updated version of the <p>state</p>.</ret>
        <detail>Use <f>stat.sample.forecast1HoltWinters</f> or <f>stat.sample.forecastHoltWinters</f> to make predictions from the state record.</detail>
        <detail>For <m>{"a_t"}</m> = the <pf>level</pf> at a time <m>{"t"}</m>, <m>{"b_t"}</m> = the <pf>trend</pf> at a time <m>{"t"}</m>, and <m>{"c_t"}</m> = the <pf>cycle</pf> at a time <m>{"t"}</m> with period <m>{"L"}</m>, <m>{"""a_t = \alpha x_t / c_{t-L} + (1 - \alpha)(a_{t-1} + b_{t-1})"""}</m>, <m>{"""b_t = \beta (a_t - a_{t-1}) + (1 - \beta) b_{t-1}"""}</m>, and <m>{"""c_t = \gamma x_t / a_t + (1 - \gamma) c_{t-L}"""}</m> for the multiplicative case and <m>{"""a_t = \alpha (x_t - c_{t-L}) + (1 - \alpha)(a_{t-1} + b_{t-1})"""}</m>, <m>{"""b_t = \beta (a_t - a_{t-1}) + (1 - \beta) b_{t-1}"""}</m>, and <m>{"""c_t = \gamma (x_t - a_t) + (1 - \gamma) c_{t-L}"""}</m> for the additive case.</detail>
        <detail>In each call to this function, <pf>cycle</pf> is rotated left, such that the first item is <m>{"c_t"}</m>.</detail>
        <error>If <p>alpha</p> is less than 0 or greater than 1, an "alpha out of range" error is raised.</error>
        <error>If <p>beta</p> is less than 0 or greater than 1, an "beta out of range" error is raised.</error>
        <error>If <p>gamm</p> is less than 0 or greater than 1, an "gamma out of range" error is raised.</error>
        <error>If <pf>cycle</pf> is empty, an "empty cycle" error is raised.</error>
      </doc>
    def apply(x: Double, alpha: Double, beta: Double, gamma: Double, record: PFARecord): PFARecord = {
      if (alpha < 0.0  ||  alpha > 1.0)
        throw new PFARuntimeException("alpha out of range")
      if (beta < 0.0  ||  beta > 1.0)
        throw new PFARuntimeException("beta out of range")
      if (gamma < 0.0  ||  gamma > 1.0)
        throw new PFARuntimeException("gamma out of range")

      val level_prev = record.get("level").asInstanceOf[java.lang.Number].doubleValue
      val trend_prev = record.get("trend").asInstanceOf[java.lang.Number].doubleValue
      val cycle_unrotated = record.get("cycle").asInstanceOf[PFAArray[Double]].toVector
      if (cycle_unrotated.isEmpty)
        throw new PFARuntimeException("empty cycle")
      val cycle_rotated = cycle_unrotated.tail :+ cycle_unrotated.head
      val cycle_prev = cycle_rotated.head
      val multiplicative = record.get("multiplicative").asInstanceOf[java.lang.Boolean].booleanValue

      if (multiplicative) {
        val level = alpha * x / cycle_prev + (1.0 - alpha) * (level_prev + trend_prev)
        val trend = beta * (level - level_prev) + (1.0 - beta) * trend_prev
        val cycle = gamma * x / level + (1.0 - gamma) * cycle_prev

        record.multiUpdate(Array("level", "trend", "cycle"), Array(level, trend, PFAArray.fromVector(cycle_rotated.updated(0, cycle))))
      }
      else {
        val level = alpha * (x - cycle_prev) + (1.0 - alpha) * (level_prev + trend_prev)
        val trend = beta * (level - level_prev) + (1.0 - beta) * trend_prev
        val cycle = gamma * (x - level) + (1.0 - gamma) * cycle_prev

        record.multiUpdate(Array("level", "trend", "cycle"), Array(level, trend, PFAArray.fromVector(cycle_rotated.updated(0, cycle))))
      }
    }
  }
  provide(UpdateHoltWintersPeriodic)

  ////   forecast1HoltWinters (Forecast1HoltWinters)
  object Forecast1HoltWinters extends LibFcn {
    val name = prefix + "forecast1HoltWinters"
    val sig = Sig(List("state" -> P.WildRecord("A", ListMap("level" -> P.Double, "trend" -> P.Double))), P.Double)
    val doc =
      <doc>
        <desc>Forecast one time-step from a state record prepared by <f>stat.state.updateHoltWinters</f> or <f>stat.state.updateHoltWintersPeriodic</f>.</desc>
        <param name="state">Record of <pf>level</pf>, <pf>trend</pf>, and possibly <pf>cycle</pf> and <pf>multiplicative</pf>.
          <paramField name="level">The constant term in an exponentially weighted linear fit of recent data.</paramField>
          <paramField name="trend">The linear term in an exponentially weighted linear fit of recent data.</paramField>
          <paramField name="cycle">The history of the previous cycle.  This field is optional, but if provided, it must be a <c>double</c> and must be accompanied by <pf>multiplicative</pf>.</paramField>
          <paramField name="multiplicative">If <c>true</c>, interpret <pf>cycle</pf> as multiplicative; if <c>false</c>, interpret it as additive.  This field is optional, but if provided, it must be a <c>boolean</c> and must be accompanied by <pf>cycle</pf>.</paramField>
        </param>
        <ret>Returns a prediction of the next time-step.</ret>
        <detail>For <m>{"a_t"}</m> = the <pf>level</pf> at a time <m>{"t"}</m>, <m>{"b_t"}</m> = the <pf>trend</pf> at a time <m>{"t"}</m>, and <m>{"c_t"}</m> = the <pf>cycle</pf> at a time <m>{"t"}</m> with period <m>{"L"}</m>, this function returns <m>{"""a_t + b_t"""}</m> (non-periodic), <m>{"""(a_t + b_t) c_{t+1}"""}</m> (multiplicative), or <m>{"""a_t + b_t + c_{t+1}"""}</m> (additive) for each <m>{"i"}</m> from <m>{"0"}</m> to <m>{"n - 1"}</m></detail>
        <error>If <pf>cycle</pf> is empty, an "empty cycle" error is raised.</error>
      </doc>

    override def javaCode(args: Seq[JavaCode], argContext: Seq[AstContext], paramTypes: Seq[Type], retType: AvroType): JavaCode = {
      val record = paramTypes(0).asInstanceOf[AvroRecord]

      val hasCycle = record.fieldOption("cycle") match {
        case None => false
        case Some(field) => {
          if (!field.avroType.accepts(AvroArray(AvroDouble())))
            throw new PFASemanticException(prefix + "forecast1HoltWinters is being given a state record type in which the \"cycle\" field is not an array of double: " + field.avroType.toString, None)
          true
        }
      }

      val hasMultiplicative = record.fieldOption("multiplicative") match {
        case None => false
        case Some(field) => {
          if (!field.avroType.accepts(AvroBoolean()))
            throw new PFASemanticException(prefix + "forecast1HoltWinters is being given a state record type in which the \"multiplicative\" field is not a boolean: " + field.avroType.toString, None)
          true
        }
      }

      if (hasCycle ^ hasMultiplicative)
        throw new PFASemanticException(prefix + "forecast1HoltWinters is being given a state record type with a \"cycle\" but no \"multiplicative\" or vice-versa", None)

      JavaCode("%s.MODULE$.apply(%s, %s)",
        this.getClass.getName,
        wrapArg(0, args, paramTypes, true),
        (if (hasCycle) "true" else "false"))
    }

    def apply(record: PFARecord, hasPeriodic: Boolean): Double = {
      val level = record.get("level").asInstanceOf[java.lang.Number].doubleValue
      val trend = record.get("trend").asInstanceOf[java.lang.Number].doubleValue

      if (!hasPeriodic) {
        level + trend
      }
      else {
        val cycle = record.get("cycle").asInstanceOf[PFAArray[Double]].toVector
        val L = cycle.size

        if (record.get("multiplicative").asInstanceOf[java.lang.Boolean].booleanValue)
          (level + trend) * cycle(1 % L)
        else
          level + trend + cycle(1 % L)
      }
    }
  }
  provide(Forecast1HoltWinters)

  ////   forecastHoltWinters (ForecastHoltWinters)
  object ForecastHoltWinters extends LibFcn {
    val name = prefix + "forecastHoltWinters"
    val sig = Sig(List("n" -> P.Int, "state" -> P.WildRecord("A", ListMap("level" -> P.Double, "trend" -> P.Double))), P.Array(P.Double))
    val doc =
      <doc>
        <desc>Forecast <p>n</p> time-steps from a state record prepared by <f>stat.state.updateHoltWinters</f> or <f>stat.state.updateHoltWintersPeriodic</f>.</desc>
        <param name="state">Record of <pf>level</pf>, <pf>trend</pf>, and possibly <pf>cycle</pf> and <pf>multiplicative</pf>.
          <paramField name="level">The constant term in an exponentially weighted linear fit of recent data.</paramField>
          <paramField name="trend">The linear term in an exponentially weighted linear fit of recent data.</paramField>
          <paramField name="cycle">The history of the previous cycle.  This field is optional, but if provided, it must be a <c>double</c> and must be accompanied by <pf>multiplicative</pf>.</paramField>
          <paramField name="multiplicative">If <c>true</c>, interpret <pf>cycle</pf> as multiplicative; if <c>false</c>, interpret it as additive.  This field is optional, but if provided, it must be a <c>boolean</c> and must be accompanied by <pf>cycle</pf>.</paramField>
        </param>
        <ret>Returns a series of predictions for the next <p>n</p> time-steps.</ret>
        <detail>For <m>{"a_t"}</m> = the <pf>level</pf> at a time <m>{"t"}</m>, <m>{"b_t"}</m> = the <pf>trend</pf> at a time <m>{"t"}</m>, and <m>{"c_t"}</m> = the <pf>cycle</pf> at a time <m>{"t"}</m> with period <m>{"L"}</m>, this function returns <m>{"""a_t + i b_t"""}</m> (non-periodic), <m>{"""(a_t + i b_t) c_{(t + i) \mbox{mod} n}"""}</m> (multiplicative), or <m>{"""a_t + i b_t + c_{(t + i) \mbox{mod} n}"""}</m> (additive) for each <m>{"i"}</m> from <m>{"1"}</m> to <m>{"n"}</m></detail>
        <error>If <pf>cycle</pf> is empty, an "empty cycle" error is raised.</error>
      </doc>

    override def javaCode(args: Seq[JavaCode], argContext: Seq[AstContext], paramTypes: Seq[Type], retType: AvroType): JavaCode = {
      val record = paramTypes(1).asInstanceOf[AvroRecord]

      val hasCycle = record.fieldOption("cycle") match {
        case None => false
        case Some(field) => {
          if (!field.avroType.accepts(AvroArray(AvroDouble())))
            throw new PFASemanticException(prefix + "forecastHoltWinters is being given a state record type in which the \"cycle\" field is not an array of double: " + field.avroType.toString, None)
          true
        }
      }

      val hasMultiplicative = record.fieldOption("multiplicative") match {
        case None => false
        case Some(field) => {
          if (!field.avroType.accepts(AvroBoolean()))
            throw new PFASemanticException(prefix + "forecastHoltWinters is being given a state record type in which the \"multiplicative\" field is not a boolean: " + field.avroType.toString, None)
          true
        }
      }

      if (hasCycle ^ hasMultiplicative)
        throw new PFASemanticException(prefix + "forecastHoltWinters is being given a state record type with a \"cycle\" but no \"multiplicative\" or vice-versa", None)

      JavaCode("%s.MODULE$.apply(%s, %s, %s)",
        this.getClass.getName,
        wrapArg(0, args, paramTypes, true),
        wrapArg(1, args, paramTypes, true),
        (if (hasCycle) "true" else "false"))
    }

    def apply(n: Int, record: PFARecord, hasPeriodic: Boolean): PFAArray[Double] = {
      val level = record.get("level").asInstanceOf[java.lang.Number].doubleValue
      val trend = record.get("trend").asInstanceOf[java.lang.Number].doubleValue

      if (!hasPeriodic) {
        PFAArray.fromVector((1 to n) map {i => level + i*trend} toVector)
      }
      else {
        val cycle = record.get("cycle").asInstanceOf[PFAArray[Double]].toVector
        val L = cycle.size

        if (record.get("multiplicative").asInstanceOf[java.lang.Boolean].booleanValue)
          PFAArray.fromVector((1 to n) map {i => (level + i*trend) * cycle(i % L)} toVector)
        else
          PFAArray.fromVector((1 to n) map {i => level + i*trend + cycle(i % L)} toVector)
      }
    }
  }
  provide(ForecastHoltWinters)

}