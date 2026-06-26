package com.player2.playerengine;

public enum FollowMode {
    NORMAL,   // default; combat governed normally by MobDefenseChain
    COWARD,   // suppress combat, flee danger, stay leashed to target
    DEFENDER; // keep combat enabled, defend target + self, stay leashed
}
