package com.example.tassmud;

import com.example.tassmud.model.StatBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StatBlock record: construction, builder, ZERO constant, equality.
 */
class StatBlockTest {

    @Test
    @DisplayName("ZERO constant has all zeroes")
    void zeroConstant() {
        StatBlock z = StatBlock.ZERO;
        assertEquals(0, z.str());
        assertEquals(0, z.dex());
        assertEquals(0, z.con());
        assertEquals(0, z.intel());
        assertEquals(0, z.wis());
        assertEquals(0, z.cha());
        assertEquals(0, z.armor());
        assertEquals(0, z.fortitude());
        assertEquals(0, z.reflex());
        assertEquals(0, z.will());
    }

    @Test
    @DisplayName("Builder produces correct values")
    void builderProducesCorrectValues() {
        StatBlock sb = StatBlock.builder()
                .str(18).dex(14).con(12).intel(10).wis(8).cha(16)
                .armor(20).fortitude(15).reflex(13).will(11)
                .build();

        assertEquals(18, sb.str());
        assertEquals(14, sb.dex());
        assertEquals(12, sb.con());
        assertEquals(10, sb.intel());
        assertEquals(8, sb.wis());
        assertEquals(16, sb.cha());
        assertEquals(20, sb.armor());
        assertEquals(15, sb.fortitude());
        assertEquals(13, sb.reflex());
        assertEquals(11, sb.will());
    }

    @Test
    @DisplayName("Builder defaults to zero for unset fields")
    void builderDefaultsToZero() {
        StatBlock sb = StatBlock.builder().str(10).build();
        assertEquals(10, sb.str());
        assertEquals(0, sb.dex(), "Unset fields default to 0");
        assertEquals(0, sb.armor());
    }

    @Test
    @DisplayName("Record equality works by value")
    void recordEquality() {
        StatBlock a = new StatBlock(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        StatBlock b = new StatBlock(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertEquals(a, b, "Same values should be equal");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("Inequality when values differ")
    void recordInequality() {
        StatBlock a = new StatBlock(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        StatBlock b = new StatBlock(1, 2, 3, 4, 5, 6, 7, 8, 9, 99);
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("Direct constructor matches builder")
    void constructorMatchesBuilder() {
        StatBlock direct = new StatBlock(14, 12, 10, 16, 8, 10, 15, 12, 11, 13);
        StatBlock built = StatBlock.builder()
                .str(14).dex(12).con(10).intel(16).wis(8).cha(10)
                .armor(15).fortitude(12).reflex(11).will(13)
                .build();
        assertEquals(direct, built);
    }
}
