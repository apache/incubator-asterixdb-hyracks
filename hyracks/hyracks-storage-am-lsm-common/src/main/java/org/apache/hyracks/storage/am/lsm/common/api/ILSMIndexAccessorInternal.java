/*
 * Copyright 2009-2013 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hyracks.storage.am.lsm.common.api;

import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.storage.am.common.api.IndexException;
import org.apache.hyracks.storage.am.common.api.TreeIndexException;

public interface ILSMIndexAccessorInternal extends ILSMIndexAccessor {

    /**
     * Force a flush of the in-memory component.
     * 
     * @throws HyracksDataException
     * @throws TreeIndexException
     */
    public void flush(ILSMIOOperation operation) throws HyracksDataException, IndexException;

    /**
     * Merge all on-disk components.
     * 
     * @throws HyracksDataException
     * @throws TreeIndexException
     */
    public void merge(ILSMIOOperation operation) throws HyracksDataException, IndexException;
}
