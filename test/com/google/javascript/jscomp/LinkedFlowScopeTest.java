/*
 * Copyright 2008 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.common.collect.ImmutableList;
import com.google.javascript.jscomp.type.FlowScope;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.jstype.JSType;

/**
 * Tests for LinkedFlowScope.
 * @author nicksantos@google.com (Nick Santos)
 */

public final class LinkedFlowScopeTest extends CompilerTypeTestCase {

  private final Node functionNode = new Node(Token.FUNCTION);
  private final Node rootNode = new Node(Token.ROOT, functionNode);
  private static final int LONG_CHAIN_LENGTH = 1050;

  private TypedScope globalScope;
  private TypedScope localScope;
  @SuppressWarnings("unused")
  private FlowScope globalEntry;
  private FlowScope localEntry;

  @Override
  public void setUp() throws Exception {
    super.setUp();

    globalScope = TypedScope.createGlobalScope(rootNode);
    globalScope.declare("globalA", null, null, null);
    globalScope.declare("globalB", null, null, null);

    localScope = new TypedScope(globalScope, functionNode);
    localScope.declare("localA", null, null, null);
    localScope.declare("localB", null, null, null);

    globalEntry = LinkedFlowScope.createEntryLattice(globalScope);
    localEntry = LinkedFlowScope.createEntryLattice(localScope);
  }

  public void testOptimize() {
    assertEquals(localEntry, localEntry.optimize());

    FlowScope child = localEntry.createChildFlowScope();
    assertEquals(localEntry, child.optimize());

    child.inferSlotType("localB", getNativeNumberType());
    assertEquals(child, child.optimize());
  }

  public void testJoin1() {
    FlowScope childA = localEntry.createChildFlowScope();
    childA.inferSlotType("localB", getNativeNumberType());

    FlowScope childAB = childA.createChildFlowScope();
    childAB.inferSlotType("localB", getNativeStringType());

    FlowScope childB = localEntry.createChildFlowScope();
    childB.inferSlotType("localB", getNativeBooleanType());

    assertTypeEquals(getNativeStringType(), childAB.getSlot("localB").getType());
    assertTypeEquals(getNativeBooleanType(), childB.getSlot("localB").getType());
    assertNull(childB.getSlot("localA").getType());

    FlowScope joined = join(childB, childAB);
    assertTypeEquals(
        createUnionType(getNativeStringType(), getNativeBooleanType()),
        joined.getSlot("localB").getType());
    assertNull(joined.getSlot("localA").getType());

    joined = join(childAB, childB);
    assertTypeEquals(
        createUnionType(getNativeStringType(), getNativeBooleanType()),
        joined.getSlot("localB").getType());
    assertNull(joined.getSlot("localA").getType());

    assertEquals("Join should be symmetric",
        join(childB, childAB), join(childAB, childB));
  }

  public void testJoin2() {
    FlowScope childA = localEntry.createChildFlowScope();
    childA.inferSlotType("localA", getNativeStringType());

    FlowScope childB = localEntry.createChildFlowScope();
    childB.inferSlotType("globalB", getNativeBooleanType());

    assertTypeEquals(getNativeStringType(), childA.getSlot("localA").getType());
    assertTypeEquals(getNativeBooleanType(), childB.getSlot("globalB").getType());
    assertNull(childB.getSlot("localB").getType());

    FlowScope joined = join(childB, childA);
    assertTypeEquals(getNativeStringType(), joined.getSlot("localA").getType());
    assertTypeEquals(getNativeBooleanType(), joined.getSlot("globalB").getType());

    joined = join(childA, childB);
    assertTypeEquals(getNativeStringType(), joined.getSlot("localA").getType());
    assertTypeEquals(getNativeBooleanType(), joined.getSlot("globalB").getType());

    assertEquals("Join should be symmetric",
        join(childB, childA), join(childA, childB));
  }

  public void testJoin3() {
    localScope.declare("localC", null, getNativeStringType(), null);
    localScope.declare("localD", null, getNativeStringType(), null);

    FlowScope childA = localEntry.createChildFlowScope();
    childA.inferSlotType("localC", getNativeNumberType());

    FlowScope childB = localEntry.createChildFlowScope();
    childA.inferSlotType("localD", getNativeBooleanType());

    FlowScope joined = join(childB, childA);
    assertTypeEquals(
        createUnionType(getNativeStringType(), getNativeNumberType()),
        joined.getSlot("localC").getType());
    assertTypeEquals(
        createUnionType(getNativeStringType(), getNativeBooleanType()),
        joined.getSlot("localD").getType());

    joined = join(childA, childB);
    assertTypeEquals(
        createUnionType(getNativeStringType(), getNativeNumberType()),
        joined.getSlot("localC").getType());
    assertTypeEquals(
        createUnionType(getNativeStringType(), getNativeBooleanType()),
        joined.getSlot("localD").getType());

    assertEquals("Join should be symmetric",
        join(childB, childA), join(childA, childB));
  }

  /**
   * Create a long chain of flow scopes where each link in the chain
   * contains one slot.
   */
  public void testLongChain1() {
    FlowScope chainA = localEntry.createChildFlowScope();
    FlowScope chainB = localEntry.createChildFlowScope();
    for (int i = 0; i < LONG_CHAIN_LENGTH; i++) {
      localScope.declare("local" + i, null, null, null);
      chainA.inferSlotType(
          "local" + i, i % 2 == 0 ? getNativeNumberType() : getNativeBooleanType());
      chainB.inferSlotType(
          "local" + i, i % 3 == 0 ? getNativeStringType() : getNativeBooleanType());

      chainA = chainA.createChildFlowScope();
      chainB = chainB.createChildFlowScope();
    }

    verifyLongChains(chainA, chainB);
  }

  /**
   * Create a long chain of flow scopes where each link in the chain
   * contains 7 slots.
   */
  public void testLongChain2() {
    FlowScope chainA = localEntry.createChildFlowScope();
    FlowScope chainB = localEntry.createChildFlowScope();
    for (int i = 0; i < LONG_CHAIN_LENGTH * 7; i++) {
      localScope.declare("local" + i, null, null, null);
      chainA.inferSlotType(
          "local" + i, i % 2 == 0 ? getNativeNumberType() : getNativeBooleanType());
      chainB.inferSlotType(
          "local" + i, i % 3 == 0 ? getNativeStringType() : getNativeBooleanType());

      if (i % 7 == 0) {
        chainA = chainA.createChildFlowScope();
        chainB = chainB.createChildFlowScope();
      }
    }

    verifyLongChains(chainA, chainB);
  }

  /**
   * Create a long chain of flow scopes where every 4 links in the chain
   * contain a slot.
   */
  public void testLongChain3() {
    FlowScope chainA = localEntry.createChildFlowScope();
    FlowScope chainB = localEntry.createChildFlowScope();
    for (int i = 0; i < LONG_CHAIN_LENGTH * 7; i++) {
      if (i % 7 == 0) {
        int j = i / 7;
        localScope.declare("local" + j, null, null, null);
        chainA.inferSlotType(
            "local" + j, j % 2 == 0 ? getNativeNumberType() : getNativeBooleanType());
        chainB.inferSlotType(
            "local" + j, j % 3 == 0 ? getNativeStringType() : getNativeBooleanType());
      }

      chainA = chainA.createChildFlowScope();
      chainB = chainB.createChildFlowScope();
    }

    verifyLongChains(chainA, chainB);
  }

  // Common chain verification for testLongChainN for all N.
  private void verifyLongChains(FlowScope chainA, FlowScope chainB) {
    FlowScope joined = join(chainA, chainB);
    for (int i = 0; i < LONG_CHAIN_LENGTH; i++) {
      assertTypeEquals(
          i % 2 == 0 ? getNativeNumberType() : getNativeBooleanType(),
          chainA.getSlot("local" + i).getType());
      assertTypeEquals(
          i % 3 == 0 ? getNativeStringType() : getNativeBooleanType(),
          chainB.getSlot("local" + i).getType());

      JSType joinedSlotType = joined.getSlot("local" + i).getType();
      if (i % 6 == 0) {
        assertTypeEquals(
            createUnionType(getNativeStringType(), getNativeNumberType()), joinedSlotType);
      } else if (i % 2 == 0) {
        assertTypeEquals(
            createUnionType(getNativeNumberType(), getNativeBooleanType()), joinedSlotType);
      } else if (i % 3 == 0) {
        assertTypeEquals(
            createUnionType(getNativeStringType(), getNativeBooleanType()), joinedSlotType);
      } else {
        assertTypeEquals(getNativeBooleanType(), joinedSlotType);
      }
    }

    assertScopesDiffer(chainA, chainB);
    assertScopesDiffer(chainA, joined);
    assertScopesDiffer(chainB, joined);
  }

  public void testFindUniqueSlot() {
    FlowScope childA = localEntry.createChildFlowScope();
    childA.inferSlotType("localB", getNativeNumberType());

    FlowScope childAB = childA.createChildFlowScope();
    childAB.inferSlotType("localB", getNativeStringType());

    FlowScope childABC = childAB.createChildFlowScope();
    childABC.inferSlotType("localA", getNativeBooleanType());

    assertNull(childABC.findUniqueRefinedSlot(childABC));
    assertTypeEquals(getNativeBooleanType(), childABC.findUniqueRefinedSlot(childAB).getType());
    assertNull(childABC.findUniqueRefinedSlot(childA));
    assertNull(childABC.findUniqueRefinedSlot(localEntry));

    assertTypeEquals(getNativeStringType(), childAB.findUniqueRefinedSlot(childA).getType());
    assertTypeEquals(getNativeStringType(), childAB.findUniqueRefinedSlot(localEntry).getType());

    assertTypeEquals(getNativeNumberType(), childA.findUniqueRefinedSlot(localEntry).getType());
  }

  public void testDiffer1() {
    FlowScope childA = localEntry.createChildFlowScope();
    childA.inferSlotType("localB", getNativeNumberType());

    FlowScope childAB = childA.createChildFlowScope();
    childAB.inferSlotType("localB", getNativeStringType());

    FlowScope childABC = childAB.createChildFlowScope();
    childABC.inferSlotType("localA", getNativeBooleanType());

    FlowScope childB = childAB.createChildFlowScope();
    childB.inferSlotType("localB", getNativeStringType());

    FlowScope childBC = childB.createChildFlowScope();
    childBC.inferSlotType("localA", getNativeNoType());

    assertScopesSame(childAB, childB);
    assertScopesDiffer(childABC, childBC);

    assertScopesDiffer(childABC, childB);
    assertScopesDiffer(childAB, childBC);

    assertScopesDiffer(childA, childAB);
    assertScopesDiffer(childA, childABC);
    assertScopesDiffer(childA, childB);
    assertScopesDiffer(childA, childBC);
  }

  public void testDiffer2() {
    FlowScope childA = localEntry.createChildFlowScope();
    childA.inferSlotType("localA", getNativeNumberType());

    FlowScope childB = localEntry.createChildFlowScope();
    childB.inferSlotType("localA", getNativeNoType());

    assertScopesDiffer(childA, childB);
  }

  private void assertScopesDiffer(FlowScope a, FlowScope b) {
    assertFalse(a.equals(b));
    assertFalse(b.equals(a));
    assertEquals(a, a);
    assertEquals(b, b);
  }

  private void assertScopesSame(FlowScope a, FlowScope b) {
    assertEquals(a, b);
    assertEquals(b, a);
    assertEquals(a, a);
    assertEquals(b, b);
  }

  @SuppressWarnings("unchecked")
  private FlowScope join(FlowScope a, FlowScope b) {
    return (new LinkedFlowScope.FlowScopeJoinOp()).apply(
        ImmutableList.of(a, b));
  }
}
