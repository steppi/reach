package org.clulab.reach.context.exec.research_exec

import java.io.File

import com.typesafe.config.ConfigFactory
import org.clulab.context.classifiers.LinearSVMContextClassifier
import org.clulab.context.utils.{AggregatedContextInstance, CodeUtils}
import org.clulab.reach.context.feature_utils.ContextFeatureUtils

object SVMPerformanceOnNewReach extends App {
  val svmWrapper = new LinearSVMContextClassifier()
  val config = ConfigFactory.load()


  val configPath = config.getString("contextEngine.params.trainedSvmPath")
  val trainedSVMInstance = svmWrapper.loadFrom(configPath)
  val classifierToUse = trainedSVMInstance.classifier match {
    case Some(x) => x
    case None => {
      null
    }
  }


  if(classifierToUse == null) throw new NullPointerException("No classifier found on which I can predict. Please make sure the SVMContextEngine class receives a valid Linear SVM classifier.")
  println(s"In svm performance class, running code")
  val labelFile = config.getString("svmContext.labelFileOldDataset")
  val labelMap = CodeUtils.generateLabelMap(labelFile).toSeq
  val specsByRow = collection.mutable.HashMap[AggregatedContextInstance, (String,String,String)]()
  val pathToParentdirToLoadNewRows = config.getString("polarityContext.aggrRowWrittenToFilePerPaper")
  val parentDirfileInstanceToLoadNewRows = new File(pathToParentdirToLoadNewRows)
  val paperDirs = parentDirfileInstanceToLoadNewRows.listFiles().filter(x => x.isDirectory && x.getName.startsWith("PMC"))
  val paperIDByNewRows = collection.mutable.ListBuffer[(String, Seq[AggregatedContextInstance])]()
  val paperIDByNewRowsSpecs = collection.mutable.ListBuffer[(String, Seq[(String,String,String)])]()
  for (paperDir <- paperDirs) {

    val listOfRowsInPaper = collection.mutable.ListBuffer[AggregatedContextInstance]()
    val listOfSpecsInPaper = collection.mutable.ListBuffer[(String,String,String)]()
    val paperID = paperDir.getName
    val rowFilesInThisPaper = paperDir.listFiles().filter(_.getName.startsWith("Aggreg"))
    for(rowFile <- rowFilesInThisPaper) {
      val rowSpecs = ContextFeatureUtils.createAggRowSpecsFromFile(rowFile)
      val row = ContextFeatureUtils.readAggRowFromFile(rowFile)
      if(!listOfRowsInPaper.contains(row)) {
        listOfRowsInPaper += row
        listOfSpecsInPaper += rowSpecs
        specsByRow ++= Map(row -> rowSpecs)
      }
    }

    val tuple2 = (paperID, listOfRowsInPaper)
    val tuple2Specs = (paperID,listOfSpecsInPaper)
    paperIDByNewRows += tuple2
    paperIDByNewRowsSpecs += tuple2Specs
  }


  val paperIDByOldRowsSpecs = labelMap.map(_._1)



  val giantTruthLabelList = collection.mutable.ListBuffer[Int]()
  val giantPredictedLabelList = collection.mutable.ListBuffer[Int]()
  val matchingLabelsInNewReachByPaper = collection.mutable.HashMap[String,Seq[(String,String,String)]]()
  val matchingLabelsInOldReachByPaper = collection.mutable.HashMap[String,Seq[(String,String,String)]]()
  for((paperID, testRows) <- paperIDByNewRows) {
    val testRowsWithMatchingLabels = collection.mutable.ListBuffer[AggregatedContextInstance]()
    val matchingLabelsPerPaperNewReach = collection.mutable.ListBuffer[(String, String, String)]()
    val matchingLabelsPerPaperOldReach = collection.mutable.ListBuffer[(String, String, String)]()
    val predictedLabelsInThisPaper = collection.mutable.ListBuffer[Int]()
    val trueLabelsInThisPaper = collection.mutable.ListBuffer[Int]()
    val possibleLabelIDsInThisPaper = labelMap.filter(_._1._1 == paperID)
    for(tester <- testRows) {

      for((labelID,label) <- possibleLabelIDsInThisPaper) {
        val specForTester = specsByRow(tester)
        if(eventsAlign(specForTester._2,labelID._2) && contextsAlign(specForTester._3,labelID._3)) {
          if(!testRowsWithMatchingLabels.contains(tester)) {
            testRowsWithMatchingLabels += tester
            trueLabelsInThisPaper += label
            matchingLabelsPerPaperNewReach += specForTester
            matchingLabelsPerPaperOldReach += labelID
          }
        }
      }


      val matchingLabelsInThisPaperNewReach = Map(paperID -> matchingLabelsPerPaperNewReach)
      matchingLabelsInNewReachByPaper ++= matchingLabelsInThisPaperNewReach




      val matchingLabelsInThisPaperOldReach = Map(paperID -> matchingLabelsPerPaperOldReach)
      matchingLabelsInOldReachByPaper  ++= matchingLabelsInThisPaperOldReach


    }



    for(validTestRow <- testRowsWithMatchingLabels) {
      val predictionByTestRow = trainedSVMInstance.predict(Seq(validTestRow))(0)
      predictedLabelsInThisPaper += predictionByTestRow
    }

    giantTruthLabelList ++= trueLabelsInThisPaper
    giantPredictedLabelList ++= predictedLabelsInThisPaper
  }


  println(s"After prediction, ${giantTruthLabelList.size} truth labels were found")
  println(s"After prediction, ${giantPredictedLabelList.size} predicted labels were found")

  println(s"The paper PMC2063868 is degenerate as per new Reach and will not appear in this analysis.")
  println(s"All events and contexts that appeared in the new Reach are 15 sentences away. We will restrict ourselves to this window even n the old dataset.")
  for((paperID,matchingLabelsNew) <-  matchingLabelsInNewReachByPaper) {
    println(s"In new Reach (Reach 2019), the paper ${paperID} has ${matchingLabelsNew.size} labels that matched.")
    // getting all the rows that were extracted by new Reach and extracting their event IDs
    val allRowSpecsInThisPaper = paperIDByNewRowsSpecs.filter(_._1 == paperID).map(_._2).flatten
    val allUniqueEventSpans = allRowSpecsInThisPaper.map(_._2).toSet
    val matchingUniqueEventSpans = matchingLabelsNew.map(_._2).toSet
    val nonMatches = allUniqueEventSpans -- matchingUniqueEventSpans
    println(s"In new Reach (Reach 2019), the paper ${paperID} has ${nonMatches.size} labels that did not match")
  }


  for((paperID, matchingLabelsOld) <- matchingLabelsInOldReachByPaper) {
    println(s"In old Reach (Reach 2015), the paper ${paperID} has ${matchingLabelsOld.size} labels that matched")
    val allLabelsInPaper = paperIDByOldRowsSpecs.filter(_._1 == paperID)
    val allUniqueEventsInPaper = allLabelsInPaper.map(_._2).toSet
    val matchingUniqueEventSpans = matchingLabelsOld.map(_._2).toSet
    val nonMatches = allUniqueEventsInPaper -- matchingUniqueEventSpans
    println(s"In old Reach (Reach 2015), the paper ${paperID} has ${nonMatches.size} labels that did not match")
  }

  println(s"In svm performance class, finished code")


  def eventsAlign(evtID1: String, evtID2: String):Boolean = {
    val tupEvt1 = parseEventIDToTup(evtID1)
    val tupEvt2 = parseEventIDToTup(evtID2)
    // the purpose of this function is to align events.
    // Since overlap or containment of one event by another is possible,
    // we need to test if one event contains the other, or vice versa. Same holds for overlap.
    eventsAlign(tupEvt1, tupEvt2) || eventsAlign(tupEvt2, tupEvt1)
  }

  def contextsAlign(ctxID1: String, ctxID2: String):Boolean = {
    ctxID1 == ctxID2
  }



  def parseEventIDToTup(eventID: String):(Int,Int,Int) = {
    val sentenceIndexString = eventID.split("from")(0).replace("in","")
    val eventTokenString = eventID.split("from")(1)
    val sentenceIndex = Integer.parseInt(sentenceIndexString)
    val eventTokenStart = Integer.parseInt(eventTokenString.split("to")(0))
    val eventTokenEnd = Integer.parseInt(eventTokenString.split("to")(1))
    (sentenceIndex,eventTokenStart,eventTokenEnd)
  }


  def eventsAlign(eventSpec1:(Int,Int,Int), eventSpec2:(Int,Int,Int)):Boolean = {
    val sameSentenceIndex = eventSpec1._1 == eventSpec2._1
    val someMatchExists = isThereSomeMatch(eventSpec1._2, eventSpec1._3, eventSpec2._2, eventSpec2._3)
    sameSentenceIndex && someMatchExists
  }

  def isThereSomeMatch(evt1Start:Int, evt1End:Int, evt2Start:Int, evt2End: Int):Boolean = {
    // exact match is when both events have the same start and end token values
    val exactMatch = ((evt1Start == evt2Start) && (evt1End == evt2End))


    // same start is when the tokens start at the same point, but one event must end before the other
    // please note that one event has to end before the other, because if they were the same, they would have already
    // been counted as an exactMatch
    val sameStart = ((evt1Start == evt2Start) && (evt1End < evt2End))


    // same end is when one event may start after the other has already started, but they end at the same token
    // again, they must start at different points, else they would have been counted as an exact match
    val sameEnd = ((evt1End == evt2End) && (evt1Start < evt2Start))

    // containment is when one event is completely inside the other event
    val containment = ((evt1Start < evt2Start) && (evt1End > evt2End))

    //overlap is when one event starts before the other, but also ends before the other.
    // the end of the first event has to be before the second event finishes.
    val overlap = ((evt1Start < evt2Start) && (evt1End < evt2End) && (evt1End > evt2Start))


    exactMatch || sameStart || sameEnd || containment || overlap
  }






}
