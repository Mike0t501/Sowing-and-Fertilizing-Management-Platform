package com.nx.vfremake

const val SEED_NODE_COUNT = 8
const val FERT_NODE_COUNT = 8
const val TOTAL_NODE_COUNT = SEED_NODE_COUNT + FERT_NODE_COUNT
const val FERT_NODE_START_INDEX = 0
const val SEED_NODE_START_INDEX = FERT_NODE_COUNT

fun nodeDisplayName(index: Int): String {
    return if (index < SEED_NODE_START_INDEX) {
        "施肥${index + 1}"
    } else {
        "播种${index - SEED_NODE_START_INDEX + 1}"
    }
}
