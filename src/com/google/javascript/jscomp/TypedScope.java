/*
 * Copyright 2015 The Closure Compiler Authors.
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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.javascript.jscomp.modules.Module;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.StaticScope;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.ObjectType;
import com.google.javascript.rhino.jstype.StaticTypedScope;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * TypedScope contains information about variables and their types. Scopes can be nested; a scope
 * points back to its parent scope.
 *
 * <p>TypedScope is also used as a lattice element for flow-sensitive type inference. As a lattice
 * element, a scope is viewed as a map from names to types. A name not in the map is considered to
 * have the bottom type. The join of two maps m1 and m2 is the map of the union of names with {@link
 * JSType#getLeastSupertype} to meet the m1 type and m2 type.
 *
 * <p>TypedScopes also have a concept of a set of 'reserved' names. These are names that will be
 * declared as actual TypedVars later in the compilation. Looking up any of these reserved names
 * will always return null, even if the name is available in a parent scope.
 *
 * @see NodeTraversal
 * @see DataFlowAnalysis
 *     <p>Several methods in this class, such as {@code isBlockScope} throw an exception when
 *     called. The reason for this is that we want to shadow methods from the parent class, to avoid
 *     calling them accidentally.
 */
public class TypedScope extends AbstractScope<TypedScope, TypedVar> implements StaticTypedScope {

  private final @Nullable TypedScope parent;
  private final int depth;
  private final @Nullable Module module;

  /** Whether this is a bottom scope for the purposes of type inference. */
  private final boolean isBottom;

  // Will shrink over time. Set to "ImmutableSet.of()" once empty (so don't try removing
  // elements from an empty set)
  private Set<String> reservedNames;

  // Scope.java contains an arguments field.
  // We haven't added it here because it's unused by the passes that need typed scopes.

  TypedScope(TypedScope parent, Node rootNode) {
    this(parent, rootNode, new LinkedHashSet<>(), null);
  }

  /**
   * Creates a new global or local TypedScope.
   *
   * @param parent The containing scope, unless this is the global scope.
   * @param rootNode Can be any node that creates a scope.
   * @param reservedNames set of simple names that will eventually be declared in this scope.
   */
  TypedScope(TypedScope parent, Node rootNode, Set<String> reservedNames, @Nullable Module module) {
    super(rootNode);
    checkChildScope(parent);
    this.parent = parent;
    this.depth = parent.depth + 1;
    this.isBottom = false;
    this.reservedNames =
        reservedNames.isEmpty() ? ImmutableSet.of() : new LinkedHashSet<>(reservedNames);
    this.module = module;
  }

  /**
   * Creates a empty Scope (bottom of the lattice).
   *
   * @param rootNode Typically a FUNCTION node or the global ROOT.
   * @param isBottom Whether this is the bottom of a lattice. Otherwise, it must be a global scope.
   */
  private TypedScope(Node rootNode, boolean isBottom) {
    super(rootNode);
    checkRootScope();
    this.parent = null;
    this.depth = 0;
    this.isBottom = isBottom;
    this.reservedNames = ImmutableSet.of();
    this.module = null;
  }

  static TypedScope createGlobalScope(Node rootNode) {
    return new TypedScope(rootNode, false);
  }

  static TypedScope createLatticeBottom(Node rootNode) {
    return new TypedScope(rootNode, true);
  }

  @Override
  public TypedScope typed() {
    return this;
  }

  /** Asserts that all reserved names in the scope have been actually declared. */
  void validateCompletelyBuilt() {
    checkState(
        reservedNames.isEmpty(),
        "Expected %s to have no reserved names, found: %s. This probably indicates a bug in"
            + " TypedScopeCreator where it is failing to declare a variable.",
        this,
        reservedNames);
    // let a (16-bit) VM garbage collect 64 bytes per TypedScope. (ImmutableSet.of() returns a
    // singleton)
    reservedNames = ImmutableSet.of();
  }

  /** Whether this is the bottom of the lattice. */
  boolean isBottom() {
    return isBottom;
  }

  @Nullable
  Module getModule() {
    return this.module;
  }

  @Override
  public int getDepth() {
    return depth;
  }

  @Override
  public TypedScope getParent() {
    return parent;
  }

  /** Gets the type of {@code this} in the current scope. */
  @Override
  public @Nullable JSType getTypeOfThis() {
    Node root = getRootNode();
    if (isGlobal()) {
      return ObjectType.cast(root.getJSType());
    } else if (NodeUtil.isNonArrowFunction(root)) {
      JSType nodeType = root.getJSType();
      if (nodeType != null && nodeType.isFunctionType()) {
        return nodeType.toMaybeFunctionType().getTypeOfThis();
      } else {
        // Executed when the current scope has not been typechecked.
        return null;
      }
    } else if (this.isStaticBlockScope()) {
      return getParent().getRootNode().getJSType();
    } else if (this.isMemberFieldDefScope() || this.isComputedFieldDefRhsScope()) {
      JSType classType = getParent().getRootNode().getJSType();
      if (root.isStaticMember()) {
        return classType;
      } else {
        return classType.assertFunctionType().getInstanceType();
      }
    } else {
      return getParent().getTypeOfThis();
    }
  }

  TypedVar declare(String name, Node nameNode,
      JSType type, CompilerInput input, boolean inferred) {
    checkState(name != null && !name.isEmpty());
    if (!reservedNames.isEmpty()) {
      // (reservedNames only contains simple, non-qualified names; so it's completely normal to
      // declare qualified names that were not 'reserved')
      reservedNames.remove(name);
    }
    TypedVar var = new TypedVar(inferred, name, nameNode, type, this, getVarCount(), input);
    declareInternal(name, var);
    return var;
  }

  @Override
  @Nullable TypedVar makeImplicitVar(ImplicitVar var) {
    if (this.isGlobal()) {
      // TODO(sdh): This is incorrect for 'global this', but since that's currently not handled
      // by this code, it's okay to bail out now until we find the root cause.  See b/74980936.
      return null;
    } else if (var.equals(ImplicitVar.EXPORTS)) {
      // Instead of using the implicit 'exports' var, we want to pretend that the var is actually
      // declared.
      return null;
    }
    return new TypedVar(false, var.name, null, getImplicitVarType(var), this, -1, null);
  }

  @Override
  protected boolean hasOwnImplicitSlot(@Nullable ImplicitVar name) {
    return name != null && !name.equals(ImplicitVar.EXPORTS) && name.isMadeByScope(this);
  }

  private @Nullable JSType getImplicitVarType(ImplicitVar var) {
    switch (var) {
      case ARGUMENTS:
        // Look for an extern named "arguments" and use its type if available.
        // TODO(sdh): consider looking for "Arguments" ctor rather than "arguments" var: this could
        // allow deleting the variable, which doesn't really belong in externs in the first place.
        TypedVar globalArgs = getGlobalScope().getVar(Var.ARGUMENTS);
        return globalArgs != null && globalArgs.isExtern() ? globalArgs.getType() : null;

      case THIS:
        return getTypeOfThis();

      case SUPER:
        // Inside a constructor, `super` may have two different types. Calls to `super()` use the
        // super-ctor type, while property accesses use the super-instance type. This logic always
        // returns the latter case.
        ObjectType receiverType = ObjectType.cast(getTypeOfThis());
        if (receiverType == null) {
          return null;
        } else if (receiverType.isInstanceType()) {
          FunctionType superclassCtor = receiverType.getSuperClassConstructor();
          return superclassCtor == null ? null : superclassCtor.getInstanceType();
        } else {
          return receiverType.getImplicitPrototype();
        }

      case EXPORTS:
        throw new AssertionError("TypedScopes should not contain an implicit 'exports'");
    }

    throw new AssertionError();
  }

  /**
   * Returns the variables in this scope that have been declared with 'var' and not yet declared
   * with a known type.
   *
   * <p>These variables can safely be set to undefined (rather than unknown) at the start of type
   * inference, and will be reset to the correct type when analyzing the first assignment to them.
   * Parameters and externs are excluded because they are not initialized in the function body, and
   * lexically-bound variables (let and const) are excluded because they are initialized when
   * inferring the LET/CONST node, which is guaranteed to occur before any use, since they are not
   * hoisted.
   */
  public Iterable<TypedVar> getDeclarativelyUnboundVarsWithoutTypes() {
    return Iterables.filter(getVarIterable(), this::isDeclarativelyUnboundVarWithoutType);
  }

  private boolean isDeclarativelyUnboundVarWithoutType(TypedVar var) {
    return var.getParentNode() != null
        && var.getType() == null
        // TODO(bradfordcsmith): update this for destructuring
        && var.getParentNode().isVar()  // NOTE: explicitly excludes let/const
        && !var.isExtern();
  }

  /**
   * Returns the slot for {@code name}, considering shadowing of qualified names.
   *
   * <p>The superclass method does not handle shadowing.
   *
   * <p>Lookup of qualified names (i.e. names with dots) executes against scopes in the following
   * precedence:
   *
   * <ol>
   *   <li>This {@link Scope}.
   *   <li>The first ancestor {@link Scope}, if any, that declares the root of the qualified name.
   *   <li>The global {@link Scope}.
   * </ol>
   *
   * <p>An example of where this is necessary: say the global scope contains "a" and "a.b" and a
   * function scope contains "a". When looking up "a.b" in the function scope, AbstractScope::getVar
   * returns "a.b". This method returns null because the global "a" is shadowed.
   */
  @Override
  public final @Nullable TypedVar getVar(String name) {
    TypedVar ownSlot = getOwnSlot(name);
    if (ownSlot != null) {
      // Micro-optimization: variables declared directly in this scope cannot have been shadowed.
      return ownSlot;
    } else if (this.getParent() == null) {
      return null;
    }
    // Find the root name and its slot.
    int dot = name.indexOf('.');
    String rootName = dot < 0 ? name : name.substring(0, dot);
    TypedVar rootVar = super.getVar(rootName); // Use the superclass method to skip string checks.

    if (dot < 0) {
      return rootVar;
    } else if (rootVar == null) {
      // Default to the global scope because externs may have qualified names with undeclared roots.
      return this.getParent().getGlobalScope().getOwnSlot(name);
    } else {
      // Qualified names 'a.b.c' are declared in the same scope as 'a', never a child scope, which
      // is why calling `getOwnSlot` is sufficient.
      return rootVar.getScope().getOwnSlot(name);
    }
  }

  final @Nullable JSType getTypeThroughNamespace(String moduleId) {
    int split = moduleId.lastIndexOf('.');
    if (split >= 0) {
      String parentName = moduleId.substring(0, split);
      String prop = moduleId.substring(split + 1);
      JSType parentType = getTypeThroughNamespace(parentName);
      if (parentType == null || parentType.toMaybeObjectType() == null) {
        return null;
      }
      return parentType.assertObjectType().getPropertyType(prop);
    } else {
      TypedVar var = this.getSlot(moduleId);
      return var != null ? var.getType() : null;
    }
  }

  @Override
  public @Nullable StaticScope getTopmostScopeOfEventualDeclaration(String name) {
    if (getOwnSlot(name) != null || reservedNames.contains(name)) {
      return this;
    } else if (this.getParent() == null) {
      return null;
    } else {
      // Recurse on the parent because it, too, may be incomplete.
      return this.getParent().getTopmostScopeOfEventualDeclaration(name);
    }
  }
}
