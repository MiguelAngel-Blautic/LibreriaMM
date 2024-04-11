package com.example.libreriamm.motiondetector

import android.util.Log
import com.example.libreriamm.camara.Person
import com.example.libreriamm.entity.Model
import com.google.firebase.ml.modeldownloader.CustomModel
import com.google.firebase.ml.modeldownloader.CustomModelDownloadConditions
import com.google.firebase.ml.modeldownloader.DownloadType
import com.google.firebase.ml.modeldownloader.FirebaseModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import timber.log.Timber
import java.nio.FloatBuffer

data class MoveNetData(
    var pointX: Float = 0f,
    var pointY: Float = 0f,
    var position: Int = 0,
    var sample: Int
)

class MotionDetector(private val model: Model) {

    interface MotionDetectorListener {
        fun onCorrectMotionRecognized(correctProb: Float)
        fun onOutputScores(outputScores: FloatArray)
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private val lock = Any()

    //TODO AHORA SON 10 MUESTRAS CADA SEGUNDO
    private val GESTURE_SAMPLES: Int = model.fldNDuration * 10

    private val NUM_DEVICES: Int = if(model.devices.filter{it.position.id != 0}.isNotEmpty()) model.devices.size else 1

    private var motionDetectorListener: MotionDetectorListener? = null

    private val outputScores: Array<FloatArray> = Array(1) { FloatArray(model.movements.size) }
    //private val outputScores: Map<Int?, Any?> = mapOf(Pair(0, 0f), Pair(0, 0f))
    private val recordingData: Array<ArrayQueue?> =
        Array(1) { ArrayQueue(GESTURE_SAMPLES * NUM_CHANNELS) }

    private var inferenceInterface: Interpreter? = null
    private var options: Interpreter.Options? = null

    var isStarted = false
        private set

    var selectMovIndex = 0

    fun setMotionDetectorListener(motionDetectorListener: MotionDetectorListener?) {
        this.motionDetectorListener = motionDetectorListener
    }

    fun start() {
        val conditions = CustomModelDownloadConditions.Builder().build()
        FirebaseModelDownloader.getInstance()
            .getModel("mm_" + model.id.toString(), DownloadType.LATEST_MODEL, conditions)
            .addOnSuccessListener { customModel: CustomModel ->
                val modelFile = customModel.file
                modelFile?.let { file ->
                    coroutineScope.launch {
                        synchronized(lock) {
                            val compatList = CompatibilityList()
                            options = Interpreter.Options()
                            if (compatList.isDelegateSupportedOnThisDevice) {
                                val delegateOptions = compatList.bestOptionsForThisDevice
                                options!!.addDelegate(GpuDelegate(delegateOptions))
                            } else {
                                // Fallback to CPU execution if GPU acceleration is not available
                                options!!.setNumThreads(NUM_LITE_THREADS)
                            }

                            inferenceInterface = Interpreter(file, options)
                            isStarted = true
                        }
                    }
                }
            }.addOnFailureListener { t: Exception? -> Timber.e(t) }
    }

    fun stop() {
        coroutineScope.cancel()
        synchronized(lock) {
            isStarted = false
        }
        inferenceInterface?.let {
            it.close()
            inferenceInterface = null
        }
    }

    fun inference(datasList: Array<Array<Array<Array<FloatArray>>>>) {
        synchronized(lock) {
            inferenceInterface?.takeIf { isStarted }?.let { interpreter ->
                var mapOfIndicesToOutputs: Map<Int, Array<FloatArray>> = mapOf(0 to arrayOf(floatArrayOf(0f, 0f)))
                interpreter.runForMultipleInputsOutputs(datasList, mapOfIndicesToOutputs)
                Log.d("Resultados", "${mapOfIndicesToOutputs[0]?.get(0)?.get(0)} || ${mapOfIndicesToOutputs[0]?.get(0)?.get(1)}")
                var totalProb = 0f
                mapOfIndicesToOutputs[0]?.get(0)?.forEach { prob -> totalProb += prob }
                val scores = FloatArray(model.movements.size)
                for (i in 0 until model.movements.size) {
                    scores[i] = ((mapOfIndicesToOutputs[0]?.get(0)?.get(i) ?: 0f) * 100f) / totalProb
                }
                motionDetectorListener?.onOutputScores(scores)
            }
        }
    }

    fun onMoveNetChanged(result: Person, sample: Int) {
        if (!isStarted) return

        result.keyPoints.forEach { keypoint ->

            val moveNetData = MoveNetData(
                pointX = keypoint.coordinate.x,
                pointY = keypoint.coordinate.y,
                position = keypoint.bodyPart.position,
                sample = sample
            )

            recordingData[0]?.let { queue ->
                queue.queueEnqueue(moveNetData.pointX)
                queue.queueEnqueue(moveNetData.pointY)
            }
        }

        //resultAUX = result

        //TODO SIEMPRE ES EL DEVICE 0 PORQUE EN ESTE MODELO SOLO HAY UN DEVICE
        //TODO NO HACE FALTA QUE NORMALICE NADA, YO LO NORMALIZO EN LA SALIDA DE LA INFERENCIA

        coroutineScope.launch {
            processData()
        }

    }


    private fun processData() {
        var scores = FloatArray(0)

        synchronized(lock) {
            inferenceInterface?.takeIf { isStarted }?.let { interpreter ->

                val testArray = Array(1) {
                    //TODO LA DURACIÓN A CAMBIADO PORQUE AHORA SE PILLAN 10 MUESTRAS EN CADA SEGUNDO
                    Array(model.fldNDuration * 10) {
                        Array(model.devices.filter{it.position.id != 0}.size * NUM_CHANNELS) { FloatArray(1) }
                    }
                }

                val sampleArray =
                    Array(GESTURE_SAMPLES) { Array(NUM_DEVICES * NUM_CHANNELS) { FloatArray(1) } }

                for (sample in 0 until GESTURE_SAMPLES) {
                    for (device in 0 until NUM_DEVICES) {
                        for (sensor in 0 until NUM_CHANNELS) {
                            val value = recordingData[0]!!.queue[sample * NUM_CHANNELS + sensor]
                            sampleArray[sample][device * NUM_CHANNELS + sensor] = floatArrayOf(value)
                        }
                    }
                }

                testArray[0] = sampleArray

                interpreter.run(testArray, outputScores)

                scores = FloatArray(model.movements.size)
                for (i in 0 until model.movements.size) {
                    scores[i] = outputScores[0][i] * 100
                }
            }
        }

        coroutineScope.launch(Dispatchers.Main) {
            if (scores.isNotEmpty()) {
                motionDetectorListener?.onOutputScores(scores)
            }
        }
    }

    companion object {
        private const val NUM_CHANNELS = 34
        private var RISE_THRESHOLD = 0.80f
        private val NUM_LITE_THREADS = 4
    }

    init {
        model.movements.sortedBy { it.fldSLabel }.forEachIndexed { index, movement ->
            if (movement.fldSLabel != "Other") selectMovIndex = index
        }
    }

}