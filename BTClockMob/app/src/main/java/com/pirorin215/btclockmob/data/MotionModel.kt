package com.pirorin215.btclockmob.data

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** ラベル付き特徴量1件（学習データ） */
@Serializable
data class LabeledFeatures(
    val label: String,
    val features: List<Float>
)

/**
 * 学習済みモーション認識モデル（重心分類器 + z-score 正規化パラメータ）。
 *
 * 推論: 入力特徴量を featMean/featStd で正規化し、各 centroid とのユークリッド距離 → 最近傍。
 * マイコン側も同じ手順（Phase 2）。
 */
@Serializable
data class MotionModel(
    val labels: List<String>,
    val centroids: List<List<Float>>,   // 正規化空間の重心 [label][dim]
    val featMean: List<Float>,
    val featStd: List<Float>
) {
    val patternCount: Int get() = labels.size

    companion object {
        /**
         * 学習: ラベル付き特徴量サンプル群から、各ラベルの重心と全体の正規化パラメータを計算する。
         * 重心は z-score 正規化空間で持つ（スケールの異なる特徴量を公平に扱うため）。
         * @return 学習できなかった場合は null
         */
        fun train(samples: List<LabeledFeatures>): MotionModel? {
            if (samples.isEmpty()) return null
            val dim = samples[0].features.size

            // 全体の平均・標準偏差（z-score 用）
            val mean = FloatArray(dim)
            for (s in samples) for (d in 0 until dim) mean[d] += s.features[d]
            for (d in 0 until dim) mean[d] /= samples.size
            val m2 = FloatArray(dim)
            for (s in samples) for (d in 0 until dim) { val diff = s.features[d] - mean[d]; m2[d] += diff * diff }
            val std = FloatArray(dim) { sqrt(m2[it] / samples.size).coerceAtLeast(1e-6f) }

            fun norm(v: List<Float>, d: Int) = (v[d] - mean[d]) / std[d]

            // ラベル別に正規化特徴量を集約し重心を計算（入力順を保つ）
            val byLabel = LinkedHashMap<String, ArrayList<FloatArray>>()
            for (s in samples) byLabel.getOrPut(s.label) { arrayListOf() }
                .add(FloatArray(dim) { norm(s.features, it) })

            val labels = byLabel.keys.toList()
            val centroids = byLabel.values.map { list ->
                val c = FloatArray(dim)
                for (v in list) for (d in 0 until dim) c[d] += v[d]
                for (d in 0 until dim) c[d] /= list.size
                c.toList()
            }
            return MotionModel(labels, centroids, mean.toList(), std.toList())
        }
    }
}
