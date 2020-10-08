/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.internal.snapshot.children;

import org.gradle.internal.snapshot.CaseSensitivity;
import org.gradle.internal.snapshot.SearchUtil;
import org.gradle.internal.snapshot.VfsRelativePath;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DefaultChildMap<T> implements ChildMap<T> {
    private final List<Entry<T>> children;
    private final CaseSensitivity caseSensitivity;

    public DefaultChildMap(List<Entry<T>> children, CaseSensitivity caseSensitivity) {
        this.children = children;
        this.caseSensitivity = caseSensitivity;
    }

    @Override
    public Optional<T> get(VfsRelativePath relativePath) {
        return Optional.empty();
    }

    @Override
    public <R> R handlePath(VfsRelativePath relativePath, PathRelationshipHandler<R> handler) {
        int childIndex = SearchUtil.binarySearch(
            children,
            candidate -> relativePath.compareToFirstSegment(candidate.getPath(), caseSensitivity)
        );
        if (childIndex >= 0) {
            return children.get(childIndex).handlePath(relativePath, childIndex, caseSensitivity, handler);
        }
        return handler.handleDifferent(-childIndex - 1);
    }

    @Override
    public T get(int index) {
        return children.get(index).getValue();
    }

    @Override
    public ChildMap<T> withNewChild(int insertBefore, String path, T newChild) {
        List<Entry<T>> newChildren = new ArrayList<>(children);
        newChildren.add(insertBefore, new Entry<>(path, newChild));
        return new DefaultChildMap<>(newChildren, caseSensitivity);
    }

    @Override
    public ChildMap<T> withReplacedChild(int childIndex, T newChild) {
        Entry<T> oldEntry = children.get(childIndex);
        if (oldEntry.getValue().equals(newChild)) {
            return this;
        }
        List<Entry<T>> newChildren = new ArrayList<>(children);
        newChildren.set(childIndex, new Entry<>(oldEntry.getPath(), newChild));
        return new DefaultChildMap<>(newChildren, caseSensitivity);
    }

    @Override
    public ChildMap<T> withRemovedChild(int childIndex) {
        List<Entry<T>> newChildren = new ArrayList<>(children);
        newChildren.remove(childIndex);
        if (newChildren.size() == 1) {
            Entry<T> onlyChild = newChildren.get(0);
            return new SingletonChildMap<>(onlyChild, caseSensitivity);
        }
        return new DefaultChildMap<>(newChildren, caseSensitivity);
    }
}
