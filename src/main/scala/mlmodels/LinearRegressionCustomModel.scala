package mlmodels

import org.apache.hadoop.mapred.InvalidInputException
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.ml.regression.{LinearRegression, LinearRegressionModel}
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.sql.DataFrame
import preprocess.PreProcessDataset
import tuning.HyperparameterTuning

object LinearRegressionCustomModel {

  def getLinearModelPath() = {
    s"${Utils.getSavePath()}/linear_regression";
  }

  def getLinearModelPipelinePath() = {
    s"${Utils.getSavePath()}/linear_regression_training"
  }

  def saveValidationDataPipeline(validationDataset: DataFrame): DataFrame = {
    val pipelineStages = PreProcessDataset.getFeaturesPipelineStages();
    val pipeline = new Pipeline()
      .setStages(pipelineStages)
      .fit(validationDataset)

    pipeline.write.overwrite().save(getLinearModelPipelinePath());
    pipeline.transform(validationDataset)
  }

  def getTransformedValidationData(validationDataset: DataFrame): DataFrame = {
    val pipeline = PipelineModel.load(getLinearModelPipelinePath());
    return pipeline.transform(validationDataset);
  }

  def trainAndSaveModel(trainingData: DataFrame): LinearRegressionModel = {
    // Declare the linear regression model.
    val lr = new LinearRegression()
      .setLabelCol(Utils.ResponseVariable)
      .setFeaturesCol("normFeatures")

    // Get the preprocessing stages from utils.
    val pipelineStages = PreProcessDataset.getFeaturesPipelineStages()

    // Create the pipeline that is going to fit the model.
    val pipeline = new Pipeline().setStages(pipelineStages ++ Array(lr));

    // We use a ParamGridBuilder to construct a grid of parameters to search over.
    val paramGrid = new ParamGridBuilder()
      .addGrid(lr.regParam, Array(0.1)) // Array(0.001, 0.01, 0.1, 0.5, 1.0, 2.0)
      //.addGrid(lr.elasticNetParam, Array(0.25, 0.5)) // Array(0.0, 0.25, 0.5, 0.75, 1.0)
      //.addGrid(lr.maxIter, Array(1, 5, 10, 20, 50)) // Array(1, 5, 10, 20, 50)
      .build()

    // We now treat the Pipeline as an Estimator, wrapping it in a CrossValidator instance.
    val cv = new CrossValidator()
      .setEstimator(pipeline)
      .setEvaluator(
        new RegressionEvaluator()
          .setLabelCol(Utils.ResponseVariable)
          .setPredictionCol("prediction")
      )
      .setEstimatorParamMaps(paramGrid)
      .setNumFolds(2) // Use 3+ in practice
      .setParallelism(2) // Evaluate up to 2 parameter settings in parallel

    // Fit the model using the cross validator.
    val model = cv.fit(trainingData)

    // Convert model to Linear regression model
    val linearModel: LinearRegressionModel = model.bestModel
      .asInstanceOf[PipelineModel]
      .stages(pipelineStages.length)
      .asInstanceOf[LinearRegressionModel]

    Utils.printModelSummary(linearModel)

    // Save model on disk.
    linearModel.write.overwrite().save(getLinearModelPath())
    println(s"Model saved on ${getLinearModelPath()}")

    return linearModel;
  }

  def loadModelFromLocalStorage(): LinearRegressionModel = {
    val model =
      LinearRegressionModel.load(getLinearModelPath());
    return model
  }

  def start(dataset: DataFrame): Unit = {
    // Split the data into training and test sets (30% held out for testing).
    val Array(trainingData, testData) = dataset.randomSplit(Array(0.8, 0.2))

    // Get the transformed test data
    var transformedTestData: DataFrame = null
    try {
      transformedTestData = getTransformedValidationData(testData);
    } catch {
      case x: InvalidInputException => {
        transformedTestData = saveValidationDataPipeline(trainingData);
        transformedTestData.show(300, false)
      }
    }

    // Evaluate the model. if it is on local storage
    // this method loads it and if not it creates it and saves it.
    try {
      println("Loading model from file system...")
      val linearModel = loadModelFromLocalStorage();
      println(
        s"RegressionModel -> ${linearModel.intercept} + ${linearModel.coefficients}"
      )

      val predictions = linearModel.transform(transformedTestData)
      HyperparameterTuning.showModelPrecision(predictions);

      println(s"Coefficients: ${linearModel.coefficients}")
      println(s"Intercept: ${linearModel.intercept}")
    } catch {
      case x: InvalidInputException => {
        println(x.getMessage)
        val linearModel = trainAndSaveModel(trainingData);
        println(
          s"RegressionModel -> ${linearModel.intercept} + ${linearModel.coefficients}"
        )

        val predictions = linearModel.transform(transformedTestData)
        HyperparameterTuning.showModelPrecision(predictions);

        println(s"Coefficients: ${linearModel.coefficients}")
        println(s"Intercept: ${linearModel.intercept}")
      }
    }
  }
}
