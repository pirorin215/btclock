package com.pirorin215.btclockmob.data

import kotlinx.serialization.Serializable

/** ラベル付き特徴量1件（学習データ） */
@Serializable
data class LabeledFeatures(
    val label: String,
    val features: List<Float>
)

/**
 * 学習済みモーション認識モデル（スケール正規化空間の重心分類器）。
 *
 * 推論: 入力特徴量を MotionFeatures.FEATURE_SCALE で除算し、各 centroid とのユークリッド距離 → 最近傍。
 * マイコン側も同じ手順。z-score は std 過小評価で破綻するため廃止（Phase 15 改）。
 */
@Serializable
data class MotionModel(
    val labels: List<String>,
    val centroids: List<List<Float>>   // スケール正規化空間の重心 [label][dim]
) {
    val patternCount: Int get() = labels.size

    companion object {
        /**
         * 学習: ラベル付き特徴量サンプル群から、各ラベルの重心を計算する。
         * 重心はスケール正規化空間（FEATURE_SCALE で除算）で持つ。
         * z-score はサンプル少＋高再現性で std が過小評価され破綻するため廃止。
         * @return 学習できなかった場合は null
         */
        fun train(samples: List<LabeledFeatures>): MotionModel? {
            if (samples.isEmpty()) return null
            val dim = samples[0].features.size
            val scale = MotionFeatures.FEATURE_SCALE

            // スケール正規化: 各特徴量を固定スケールで除算（z-score 廃止・std 過小評価対策）
            fun norm(v: List<Float>, d: Int) = v[d] / scale[d]

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
            return MotionModel(labels, centroids)
        }
    }
}
