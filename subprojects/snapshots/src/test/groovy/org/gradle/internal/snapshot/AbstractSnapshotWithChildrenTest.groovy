/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.snapshot


import spock.lang.Specification

import javax.annotation.Nullable
import java.util.stream.Collectors

import static org.gradle.internal.snapshot.CaseSensitivity.CASE_SENSITIVE

abstract class AbstractSnapshotWithChildrenTest<NODE extends FileSystemNode, CHILD extends FileSystemNode> extends Specification {
    NODE initialRoot
    ChildMap<CHILD> children
    VfsRelativePath searchedPath

    List<FileSystemNode> removedNodes = []
    List<FileSystemNode> addedNodes = []

    SnapshotHierarchy.NodeDiffListener diffListener = new SnapshotHierarchy.NodeDiffListener() {
        @Override
        void nodeRemoved(FileSystemNode node) {
            removedNodes.add(node)
        }

        @Override
        void nodeAdded(FileSystemNode node) {
            addedNodes.add(node)
        }
    }

    String selectedChildPath
    /**
     * The child, if any, which has a common prefix with the selected path.
     */
    FileSystemNode selectedChild

    abstract protected NODE createInitialRootNode(ChildMap<CHILD> children);

    abstract protected CHILD mockChild()

    void setupTest(VirtualFileSystemTestSpec spec) {
        this.children = createChildren(spec.childPaths)
        this.initialRoot = createInitialRootNode(children)
        this.searchedPath = spec.searchedPath
        this.selectedChildPath = spec.selectedChildPath
        if (selectedChildPath != null) {
            def selectedChildIndex = children.indexOf(selectedChildPath, CASE_SENSITIVE)
            this.selectedChild = selectedChildIndex == -1 ? null : children.get(selectedChildIndex)
        }
    }

    ChildMap<CHILD> createChildren(List<String> pathsToParent) {
        return ChildMap.of(pathsToParent.stream()
            .sorted(PathUtil.getPathComparator(CASE_SENSITIVE))
            .map { childPath -> new ChildMap.Entry(childPath, mockChild()) }
            .collect(Collectors.toList()))
    }

    ChildMap<FileSystemNode> childrenWithSelectedChildReplacedBy(FileSystemNode replacement) {
        children.withReplacedChild(children.indexOf(selectedChildPath, CASE_SENSITIVE), selectedChildPath, replacement)
    }

    ChildMap<FileSystemNode> childrenWithSelectedChildReplacedBy(String replacementPath, FileSystemNode replacement) {
        children.withReplacedChild(children.indexOf(selectedChildPath, CASE_SENSITIVE), replacementPath, replacement)
    }

    ChildMap<FileSystemNode> childrenWithAdditionalChild(String path, FileSystemNode newChild) {
        children.handlePath(VfsRelativePath.of(path), CASE_SENSITIVE, new ChildMap.PathRelationshipHandler<ChildMap<FileSystemNode>>() {
            @Override
            ChildMap<FileSystemNode> handleDescendant(String childPath, int childIndex) {
                throw new AssertionError()
            }

            @Override
            ChildMap<FileSystemNode> handleAncestor(String childPath, int childIndex) {
                throw new AssertionError()
            }

            @Override
            ChildMap<FileSystemNode> handleSame(int childIndex) {
                throw new AssertionError()
            }

            @Override
            ChildMap<FileSystemNode> handleCommonPrefix(int commonPrefixLength, String childPath, int childIndex) {
                return PathUtil.getPathComparator(CASE_SENSITIVE).compare(path, childPath) < 0
                    ? children.withNewChild(childIndex, path, newChild)
                    : children.withNewChild(childIndex + 1, path, newChild)
            }

            @Override
            ChildMap<FileSystemNode> handleDifferent(int indexOfNextBiggerChild) {
                return children.withNewChild(indexOfNextBiggerChild, path, newChild)
            }
        })
    }

    ChildMap<CHILD> childrenWithSelectedChildRemoved() {
        children.withRemovedChild(children.indexOf(selectedChildPath, CASE_SENSITIVE))
    }

    CHILD getNodeWithIndexOfSelectedChild(ChildMap<CHILD> newChildren) {
        int index = children.indexOf(selectedChildPath, CASE_SENSITIVE)
        return newChildren.get(index)
    }

    String getCommonPrefix() {
        return selectedChildPath.substring(0, searchedPath.lengthOfCommonPrefix(selectedChildPath, CASE_SENSITIVE))
    }

    String getPathFromCommonPrefix() {
        return searchedPath.suffixStartingFrom(commonPrefix.length() + 1).asString
    }

    String getSelectedChildPathFromCommonPrefix() {
        return selectedChildPath.substring(commonPrefix.length() + 1)
    }

    def getDescendantSnapshotOfSelectedChild(@Nullable MetadataSnapshot foundSnapshot) {
        def descendantOffset = selectedChildPath.length() + 1
        1 * selectedChild.getSnapshot(searchedPath.suffixStartingFrom(descendantOffset), CASE_SENSITIVE) >> Optional.ofNullable(foundSnapshot)
    }

    def getDescendantNodeOfSelectedChild(ReadOnlyFileSystemNode foundNode) {
        def descendantOffset = selectedChildPath.length() + 1
        1 * selectedChild.getNode(searchedPath.suffixStartingFrom(descendantOffset), CASE_SENSITIVE) >> foundNode
    }

    def invalidateDescendantOfSelectedChild(@Nullable FileSystemNode invalidatedChild) {
        def descendantOffset = selectedChildPath.length() + 1
        1 * selectedChild.invalidate(searchedPath.suffixStartingFrom(descendantOffset), CASE_SENSITIVE, _) >> Optional.ofNullable(invalidatedChild)
    }

    @SuppressWarnings("GrMethodMayBeStatic")
    void noMoreInteractions() {
        _ * _.pathToParent
        0 * _
    }

    /**
     * Different lists of relative paths of the initial children of the node under test.
     */
    static final INITIAL_CHILD_CONSTELLATIONS = [
        ['name'],
        ['name', 'name1'],
        ['name', 'name1', 'name12'],
        ['name', 'name1', 'name12', 'name2', 'name21'],
        ['name/some'],
        ['name/some/other'],
        ['name/some', 'name2'],
        ['name/some', 'name2/other'],
        ['name/some', 'name2/other'],
        ['name', 'name1/some', 'name2/other/third'],
        ['aa/b1', 'ab/a1', 'name', 'name1/some', 'name2/other/third'],
        ("a".."z").toList(),
    ]

    /**
     * The queried/updated path has no common prefix with any of the initial children of the node under test.
     *
     * E.g.
     *   path: 'name0/some
     *   children: ['name/some', 'other']
     * or
     *   path: 'completelyDifferent'
     *   children: ['name/some', 'other']
     */
    static final List<VirtualFileSystemTestSpec> NO_COMMON_PREFIX = INITIAL_CHILD_CONSTELLATIONS.collectMany { childPaths ->
        childPaths.collect { childPath ->
            def firstSlash = childPath.indexOf('/')
            String newChildPath = firstSlash > -1
                ? "${childPath.substring(0, firstSlash)}0${childPath.substring(firstSlash)}"
                : "${childPath}0"
            new VirtualFileSystemTestSpec(childPaths, newChildPath, null)
        } + new VirtualFileSystemTestSpec(childPaths, 'completelyDifferent', null)
    } + new VirtualFileSystemTestSpec([], 'different', null)

    /**
     * The queried/updated path has a true common prefix with one of the initial children of the node under test.
     *
     * E.g.
     *   path: 'name/different'
     *   children: ['name/some', 'other']
     */
    static final List<VirtualFileSystemTestSpec> COMMON_PREFIX = INITIAL_CHILD_CONSTELLATIONS.collectMany { childPaths ->
        childPaths.findAll { it.contains('/') } collectMany { childPath ->
            parentPaths(childPath).collect {
                new VirtualFileSystemTestSpec(childPaths, "${it}/different", childPath)
            }
        }
    }

    /**
     * The queried/updated path is a prefix of one of the initial children of the node under test.
     *
     * E.g.
     *   path: 'name'
     *   children: ['name/some', 'other']
     */
    static final List<VirtualFileSystemTestSpec> IS_PREFIX_OF_CHILD = INITIAL_CHILD_CONSTELLATIONS.collectMany { childPaths ->
        childPaths.findAll { it.contains('/') } collectMany { childPath ->
            parentPaths(childPath).collect { parentPath ->
                new VirtualFileSystemTestSpec(childPaths, parentPath, findPathWithParent(childPaths, parentPath))
            }
        }
    }

    /**
     * The queried/updated path is one of the initial children of the node under test.
     *
     * E.g.
     *   path: 'name/some'
     *   children: ['name/some', 'other']
     */
    static final List<VirtualFileSystemTestSpec> SAME_PATH = INITIAL_CHILD_CONSTELLATIONS.collectMany { childPaths ->
        childPaths.collect {
            new VirtualFileSystemTestSpec(childPaths, it, it)
        }
    }

    /**
     * One of the initial children of the node under test is a prefix of the queried/updated path.
     *
     * E.g.
     *   path: 'name/some/descendant'
     *   children: ['name/some', 'other']
     */
    static final List<VirtualFileSystemTestSpec> CHILD_IS_PREFIX = INITIAL_CHILD_CONSTELLATIONS.collectMany { childPaths ->
        childPaths.collect {
            new VirtualFileSystemTestSpec(childPaths, "${it}/descendant", it)
        }
    }

    private static String findPathWithParent(List<String> childPaths, String parentPath) {
        childPaths.find { VfsRelativePath.of(it, 0).hasPrefix(parentPath, CASE_SENSITIVE) }
    }

    private static List<String> parentPaths(String childPath) {
        (childPath.split('/') as List).inits().tail().init().collect { it.join('/') }
    }
}
