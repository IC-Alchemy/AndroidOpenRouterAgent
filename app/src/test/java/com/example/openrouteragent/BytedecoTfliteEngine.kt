package com.example.openrouteragent

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.tensorflowlite.TfLiteInterpreter
import org.bytedeco.tensorflowlite.TfLiteInterpreterOptions
import org.bytedeco.tensorflowlite.TfLiteModel
import org.bytedeco.tensorflowlite.TfLiteTensor
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteInterpreterAllocateTensors
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteInterpreterCreate
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteInterpreterDelete
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteInterpreterGetInputTensor
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteInterpreterGetInputTensorCount
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteInterpreterGetOutputTensor
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteInterpreterGetOutputTensorCount
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteInterpreterInvoke
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteInterpreterOptionsCreate
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteInterpreterOptionsDelete
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteInterpreterOptionsSetNumThreads
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteModelCreate
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteModelDelete
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteTensorByteSize
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteTensorCopyFromBuffer
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteTensorCopyToBuffer
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteTensorDim
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteTensorName
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteTensorNumDims
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteTensorQuantizationParams
import org.bytedeco.tensorflowlite.global.tensorflowlite.TfLiteTensorType
import org.bytedeco.tensorflowlite.global.tensorflowlite.kTfLiteFloat32
import org.bytedeco.tensorflowlite.global.tensorflowlite.kTfLiteInt32
import org.bytedeco.tensorflowlite.global.tensorflowlite.kTfLiteInt64
import org.bytedeco.tensorflowlite.global.tensorflowlite.kTfLiteInt8
import org.bytedeco.tensorflowlite.global.tensorflowlite.kTfLiteOk
import org.bytedeco.tensorflowlite.global.tensorflowlite.kTfLiteUInt8
import java.io.File

class BytedecoTfliteEngine(modelBytes: ByteArray) : TfliteInferenceEngine {
    constructor(modelFile: File) : this(modelFile.readBytes())

    private val modelPointer = BytePointer(*modelBytes)
    private val model: TfLiteModel
    private val options: TfLiteInterpreterOptions
    private val interpreter: TfLiteInterpreter

    init {
        model = TfLiteModelCreate(modelPointer, modelBytes.size.toLong())
            ?: error("Bytedeco TfLiteModelCreate returned null")
        options = TfLiteInterpreterOptionsCreate()
            ?: run {
                TfLiteModelDelete(model)
                modelPointer.close()
                error("Bytedeco TfLiteInterpreterOptionsCreate returned null")
            }
        TfLiteInterpreterOptionsSetNumThreads(options, 2)
        interpreter = TfLiteInterpreterCreate(model, options)
            ?: run {
                TfLiteInterpreterOptionsDelete(options)
                TfLiteModelDelete(model)
                modelPointer.close()
                error("Bytedeco TfLiteInterpreterCreate returned null")
            }
        val status = TfLiteInterpreterAllocateTensors(interpreter)
        if (status != kTfLiteOk) {
            close()
            error("Bytedeco TfLiteInterpreterAllocateTensors failed with status $status")
        }
    }

    override val inputCount: Int
        get() = TfLiteInterpreterGetInputTensorCount(interpreter)

    override val outputCount: Int
        get() = TfLiteInterpreterGetOutputTensorCount(interpreter)

    override fun inputSpec(index: Int): TensorSpec =
        tensorToSpec(TfLiteInterpreterGetInputTensor(interpreter, index))

    override fun outputSpec(index: Int): TensorSpec =
        tensorToSpec(TfLiteInterpreterGetOutputTensor(interpreter, index))

    override fun run(inputs: Array<Any>, outputs: MutableMap<Int, Any>) {
        require(inputs.size == inputCount) { "Expected $inputCount inputs, got ${inputs.size}" }
        for (i in inputs.indices) {
            val tensor = TfLiteInterpreterGetInputTensor(interpreter, i)
            copyInputToTensor(tensor, inputs[i])
        }
        val status = TfLiteInterpreterInvoke(interpreter)
        check(status == kTfLiteOk) { "TfLiteInterpreterInvoke failed with status $status" }
        for ((index, target) in outputs) {
            val tensor = TfLiteInterpreterGetOutputTensor(interpreter, index)
            copyTensorToOutput(tensor, target)
        }
    }

    override fun close() {
        TfLiteInterpreterDelete(interpreter)
        TfLiteInterpreterOptionsDelete(options)
        TfLiteModelDelete(model)
        modelPointer.close()
    }

    private fun tensorToSpec(tensor: TfLiteTensor): TensorSpec {
        val dims = IntArray(TfLiteTensorNumDims(tensor)) { d -> TfLiteTensorDim(tensor, d) }
        val q = TfLiteTensorQuantizationParams(tensor)
        val type = when (TfLiteTensorType(tensor)) {
            kTfLiteFloat32 -> TensorDataType.FLOAT32
            kTfLiteInt32 -> TensorDataType.INT32
            kTfLiteInt8 -> TensorDataType.INT8
            kTfLiteUInt8 -> TensorDataType.UINT8
            kTfLiteInt64 -> TensorDataType.INT64
            else -> TensorDataType.OTHER
        }
        return TensorSpec(
            name = TfLiteTensorName(tensor)?.string.orEmpty(),
            shape = dims,
            dataType = type,
            scale = q.scale(),
            zeroPoint = q.zero_point(),
        )
    }

    private fun copyInputToTensor(tensor: TfLiteTensor, input: Any) {
        val byteSize = TfLiteTensorByteSize(tensor)
        when (input) {
            is Array<*> -> {
                val first = input.firstOrNull()
                when (first) {
                    is IntArray -> {
                        val totalInts = input.sumOf { (it as IntArray).size }
                        val flat = IntArray(totalInts)
                        var offset = 0
                        for (row in input) {
                            val arr = row as IntArray
                            arr.copyInto(flat, offset)
                            offset += arr.size
                        }
                        IntPointer(*flat).use { ptr ->
                            check(TfLiteTensorCopyFromBuffer(tensor, ptr, byteSize) == kTfLiteOk)
                        }
                    }
                    is FloatArray -> {
                        val totalFloats = input.sumOf { (it as FloatArray).size }
                        val flat = FloatArray(totalFloats)
                        var offset = 0
                        for (row in input) {
                            val arr = row as FloatArray
                            arr.copyInto(flat, offset)
                            offset += arr.size
                        }
                        FloatPointer(*flat).use { ptr ->
                            check(TfLiteTensorCopyFromBuffer(tensor, ptr, byteSize) == kTfLiteOk)
                        }
                    }
                    is ByteArray -> {
                        val totalBytes = input.sumOf { (it as ByteArray).size }
                        val flat = ByteArray(totalBytes)
                        var offset = 0
                        for (row in input) {
                            val arr = row as ByteArray
                            arr.copyInto(flat, offset)
                            offset += arr.size
                        }
                        BytePointer(*flat).use { ptr ->
                            check(TfLiteTensorCopyFromBuffer(tensor, ptr, byteSize) == kTfLiteOk)
                        }
                    }
                    else -> error("Unsupported input array element type: ${first?.javaClass}")
                }
            }
            is IntArray -> IntPointer(*input).use { ptr ->
                check(TfLiteTensorCopyFromBuffer(tensor, ptr, byteSize) == kTfLiteOk)
            }
            is FloatArray -> FloatPointer(*input).use { ptr ->
                check(TfLiteTensorCopyFromBuffer(tensor, ptr, byteSize) == kTfLiteOk)
            }
            is ByteArray -> BytePointer(*input).use { ptr ->
                check(TfLiteTensorCopyFromBuffer(tensor, ptr, byteSize) == kTfLiteOk)
            }
            else -> error("Unsupported input buffer type: ${input.javaClass}")
        }
    }

    private fun copyTensorToOutput(tensor: TfLiteTensor, output: Any) {
        val byteSize = TfLiteTensorByteSize(tensor)
        when (output) {
            is Array<*> -> {
                val first = output.firstOrNull()
                when (first) {
                    is ByteArray -> {
                        val totalBytes = output.sumOf { (it as ByteArray).size }
                        BytePointer(totalBytes.toLong()).use { ptr ->
                            check(TfLiteTensorCopyToBuffer(tensor, ptr, byteSize) == kTfLiteOk)
                            val flat = ByteArray(totalBytes)
                            ptr.get(flat)
                            var offset = 0
                            for (row in output) {
                                val arr = row as ByteArray
                                flat.copyInto(arr, 0, offset, offset + arr.size)
                                offset += arr.size
                            }
                        }
                    }
                    is FloatArray -> {
                        val totalFloats = output.sumOf { (it as FloatArray).size }
                        FloatPointer(totalFloats.toLong()).use { ptr ->
                            check(TfLiteTensorCopyToBuffer(tensor, ptr, byteSize) == kTfLiteOk)
                            val flat = FloatArray(totalFloats)
                            ptr.get(flat)
                            var offset = 0
                            for (row in output) {
                                val arr = row as FloatArray
                                flat.copyInto(arr, 0, offset, offset + arr.size)
                                offset += arr.size
                            }
                        }
                    }
                    else -> error("Unsupported output array element type: ${first?.javaClass}")
                }
            }
            is ByteArray -> BytePointer(output.size.toLong()).use { ptr ->
                check(TfLiteTensorCopyToBuffer(tensor, ptr, byteSize) == kTfLiteOk)
                ptr.get(output)
            }
            is FloatArray -> FloatPointer(output.size.toLong()).use { ptr ->
                check(TfLiteTensorCopyToBuffer(tensor, ptr, byteSize) == kTfLiteOk)
                ptr.get(output)
            }
            else -> error("Unsupported output buffer type: ${output.javaClass}")
        }
    }
}
