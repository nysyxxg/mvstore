package niopagestore

import nioobjects.TXIdentifier
import niopageentries.*
import niopageobjects.NioPageFile
import niopageobjects.NioPageFilePage
import niopageobjects.PAGESIZE
import java.util.*


class NioBTreeEntry(val key: NioPageEntry, val values: ListPageEntry, val indexEntry: NioPageFilePage.IndexEntry?) : NioPageEntry {
    constructor(key: NioPageEntry, value: NioPageEntry) : this(key, ListPageEntry(mutableListOf(value)), null)

    var childPageNumber: Int? = null  // if not null points to a page containing bigger keys then this

    val isInnerNode
        get() = childPageNumber != null

    val isLeafNode
        get() = !isInnerNode

    override fun compareTo(other: NioPageEntry): Int {
        if (this === other) return 0
        if (javaClass != other.javaClass)
            return key.hashCode().compareTo(other.hashCode())
        other as NioBTreeEntry
        return key.compareTo(other.key)
    }

    // constructor(key: NioPageEntry) : this(key, null)
    override val length: Short
        get() = toShort(key.length + values.length + (if (childPageNumber != null) 4 else 0))
    override val type: NioPageEntryType
        get() = NioPageEntryType.ELSE

    override fun marshalTo(file: NioPageFile, offset: Long) {
        key.marshalTo(file, offset)
        values.marshalTo(file, offset + key.length)
        if (childPageNumber != null) {
            file.setInt(offset + key.length + values.length, childPageNumber ?: 0)
        } else {
            values.marshalTo(file, offset + key.length)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NioBTreeEntry) return false

        if (key != other.key) return false
        if (values != other.values) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + values.hashCode()
        return result
    }

    fun addValue(value: NioPageEntry) {
        values.a.add(value)
    }

    fun removeValue(value: NioPageEntry) {
        values.a.remove(value)
    }

    override fun toString(): String {
        return "NioBTreeEntry(key=$key, indexEntry=$indexEntry, childPageNumber=$childPageNumber)"
    }

}

fun unmarshallEntry(page: NioPageFilePage, indexEntry: NioPageFilePage.IndexEntry): NioBTreeEntry {
    assert(!indexEntry.deleted)
    val offset = indexEntry.offsetInFile(page)
    val key = unmarshalFrom(page.file, offset)
    val values = unmarshalFrom(page.file, offset + key.length)
    val keyValueLen = key.length + values.length
    values as ListPageEntry
    val result = NioBTreeEntry(key, values, indexEntry)
    if (keyValueLen < indexEntry.len) {
        assert(indexEntry.len - keyValueLen == 4)
        val childpage = page.file.getInt(offset + keyValueLen)
        result.childPageNumber = childpage
    }
    return result
}

class NioBTree(val file: NioPageFile) {
    var root: NioPageFilePage? = null

    private fun insert(page: NioPageFilePage, toInsert: NioBTreeEntry, forceUnique: Boolean): NioBTreeEntry? {
        val len = toInsert.length
        val pageEntries = getSortedEntries(page)

        val greater = pageEntries.find { it >= toInsert }
        val toInsertIndex = if (greater == null) pageEntries.size else pageEntries.indexOf(greater)
        if (greater != null && greater.key == toInsert.key) {
            assert(!forceUnique, { "trying to reinsert but key is yet there" })
            // the key is found in the current page, multiples are allowed, so add the value to the BTreeEntryValues-List
            if (page.allocationFitsIntoPage(toInsert.values.a[0].length)) {
                greater.addValue(toInsert.values.a[0])
                if (greater.indexEntry == null)
                    throw AssertionError("should not be null here")
                else
                    page.remove(greater.indexEntry)
                page.add(greater)
            } else {
                throw NotImplementedError("TODO: handle split or link to extra pages because of additional value not fitting into page")
                // TODO: handle split or link to extra pages because of additional value not fitting into page
            }
        } else
            if (greater == null && pageEntries.size == 0
                    || greater == null && pageEntries.first().isLeafNode
                    || greater != null && greater.isLeafNode) {
                return insertAndSplitIfNecessary(page, toInsert, toInsertIndex, pageEntries, false)
            } else {
                // found in inner Node
                assert(
                        greater == null && pageEntries.first().isInnerNode
                                || greater != null && greater.isInnerNode)
                assert(toInsertIndex > 0)  // leftmost element must be smaller than all
                if (toInsert.childPageNumber == null) {
                    // need to go until found position in leaf
                    val childPageNumber = pageEntries[toInsertIndex - 1].childPageNumber
                    if (childPageNumber == null)
                        throw AssertionError("expected childpagenumber to be != null in inner node")
                    else {
                        val nextLayerPage = NioPageFilePage(file, childPageNumber)
                        val result = insert(nextLayerPage, toInsert, forceUnique)
                        if (result != null) {
                            // insert this in current page since a split has occurred
                            return insert(page, result, forceUnique)
                        }
                    }
                } else {
                    // insert into inner page
                    return insertAndSplitIfNecessary(page, toInsert, toInsertIndex, pageEntries, true)
                }
            }
        return null
    }

    private fun getSortedEntries(page: NioPageFilePage): MutableList<NioBTreeEntry> {
        val pageEntries = mutableListOf<NioBTreeEntry>()
        page.indexEntries().forEach {
            if (!it.deleted)
                pageEntries.add(unmarshallEntry(page, it))
        }

        pageEntries.sort()
        return pageEntries
    }

    private fun insertAndSplitIfNecessary(page: NioPageFilePage,
                                          toInsert: NioBTreeEntry,
                                          toInsertIndex: Int,
                                          pageEntries: MutableList<NioBTreeEntry>,
                                          isInnerPage: Boolean): NioBTreeEntry? {
        if (page.allocationFitsIntoPage(toInsert.length)) {
            page.add(toInsert)
            return null
        } else {
            pageEntries.add(toInsertIndex, toInsert)
            val completeLength = pageEntries.sumBy { it.length.toInt() } + toInsert.length
            var currentSum = 0
            var splitEntry: NioBTreeEntry? = null

            var insertLeft = false

            val newPage = file.newPage()
            for (e in pageEntries.iterator()) {
                currentSum += e.length
                if (currentSum >= completeLength / 2) {
                    // limit for left page reached
                    // first note split Entry that should be propagated
                    if (splitEntry == null) {
                        // this entry will be returned to be inserted into the parent page
                        splitEntry = e
                        if (isInnerPage) {
                            assert(e.childPageNumber != null)
                            val firstEntry = NioBTreeEntry(EmptyPageEntry(), EmptyPageEntry())
                            // save here childPageNumber of splitEntry, so before first element in page,
                            // all elements of page on which the splitEntry points are positions
                            firstEntry.childPageNumber = splitEntry.childPageNumber
                            newPage.add(firstEntry)
                        }
                        splitEntry.childPageNumber = newPage.number
                    } else {
                        // then add entry to newpage, because it is right from the splitEntry
                        newPage.add(e)
                    }

                    if (!(e === toInsert) && e.indexEntry != null) {
                        // remove everything right of limit (including splitentry), except if newly to be inserted (not yet in tree)
                        page.remove(e.indexEntry)
                    }
                } else {
                    if (e === toInsert)
                    // don't add new entry yet, since page-index might get compacted during add and btw.
                    // can't add otherwise the split wasn't necessary
                        insertLeft = true
                }
            }
            // entry to be inserted found on the left side during limit calculation
            if (insertLeft) {
                // so the new entry not inserted yet during splitting
                page.add(toInsert)
            }
            return splitEntry
        }
    }

    fun getPrepareRoot(): NioPageFilePage {
        val result =
                if (!file.usedPagesIterator().hasNext()) {
                    val result = file.newPage()
                    val leaf = file.newPage()
                    val firstRootElement = NioBTreeEntry(EmptyPageEntry(), EmptyPageEntry())
                    firstRootElement.childPageNumber = leaf.number
                    result.add(firstRootElement)
                    file.rootPage = result.number
                    result
                } else {
                    NioPageFilePage(file, file.rootPage)
                }
        root = result
        return result
    }

    fun insert(tx: TXIdentifier, key: NioPageEntry, value: NioPageEntry) {
        val toInsert = NioBTreeEntry(key, value)

        insertAndFixRoot(toInsert, false)
        val message = check()
        if (message.length > 0) println(message)
    }

    private fun insertAndFixRoot(toInsert: NioBTreeEntry, forceUnique: Boolean) {
        val splitElement = insert(getPrepareRoot(), toInsert, forceUnique)
        fixRoot(splitElement)
    }

    private fun fixRoot(splitElement: NioBTreeEntry?) {
        if (splitElement != null) {
            val newRoot = file.newPage()
            val firstRootElement = NioBTreeEntry(EmptyPageEntry(), EmptyPageEntry())
            firstRootElement.childPageNumber = root!!.number
            newRoot.add(firstRootElement)
            newRoot.add(splitElement)
            file.rootPage = newRoot.number
            root = newRoot
        }
    }

    private fun delete(page: NioPageFilePage, toDelete: NioPageEntry, value: NioPageEntry, toReInsert: MutableList<NioBTreeEntry>): Pair<Boolean, NioBTreeEntry?> {
        val pageEntries = getSortedEntries(page)
        val greater = pageEntries.find { it >= toDelete }
        val index = if (greater == null) pageEntries.size else pageEntries.indexOf(greater)
        if (greater != null && greater.compareTo(toDelete) != 0 || greater == null) {  // not found yet
            assert(index > 0)
            val referingEntry = pageEntries[index - 1]
            val nextChildPageNo = referingEntry.childPageNumber ?: throw IndexOutOfBoundsException("entry to be deleted not found in tree")
            val child = NioPageFilePage(file, nextChildPageNo)
            val result = delete(child, toDelete, value, toReInsert)
            val newEntry = result.second
            if (newEntry != null) {
                assert(result.first)
                return Pair(true, insert(page, newEntry, false))
            } else {
                if (result.first)  // make sure to don't handle anything further since a split occured during the call with possible changes
                    return Pair(true, null)
            }
            if (child.freeSpace() > (PAGESIZE.toInt()) * 2 / 3) {
                if (child.empty()) {
                    handleEmptyChildPage(page, child, pageEntries, index - 1, toReInsert)
                    return Pair(false, null)
                }
                // try to remove child
                if (index > 1) {
                    if (!tryMergeChildToLeftPage(pageEntries, index, child, page)) {
                        if (index < pageEntries.size) {
                            tryMergeRightToChild(pageEntries, index, child, page)
                        }
                    }
                } else if (index < pageEntries.size) {
                    tryMergeRightToChild(pageEntries, index, child, page)
                }
            }
        } else {
            val greaterIndexEntry = greater.indexEntry ?: throw AssertionError("sorted pageentries generated with indexentry null")
            val orgSize = greater.values.a.size
            if (value !is EmptyPageEntry)
                greater.values.a.remove(value)

            if (greater.values.a.size < orgSize || value is EmptyPageEntry) {
                if (greater.values.a.size == 0 || value is EmptyPageEntry) {
                    // entry has been found and can be deleted
                    val nextChildPageNo = pageEntries[index].childPageNumber
                    if (nextChildPageNo == null) {
                        page.remove(greater.indexEntry)
                    } else {
                        val child = NioPageFilePage(file, nextChildPageNo)

                        var entryForReplacement = findSmallestEntry(child)
                        assert(entryForReplacement.childPageNumber == null)
                        // now removeButKeepIndexEntry it from child, no split can occur, since a leaf-element will be removed
                        assert(!delete(child, entryForReplacement, EmptyPageEntry(), toReInsert).first)
                        entryForReplacement.childPageNumber = greater.childPageNumber
                        page.remove(greater.indexEntry)
                        if (child.freeSpace() > (PAGESIZE.toInt()) * 2 / 3) {
                            if (child.empty()) {
                                file.freePage(child)
                                entryForReplacement.childPageNumber = null
                                toReInsert.add(entryForReplacement)
                                return Pair(false, null)
                            }
                            assert(index > 0)
                            val prevChildPageNo = pageEntries[index - 1].childPageNumber
                            val leftPage = (if (prevChildPageNo == null) null else NioPageFilePage(file, prevChildPageNo)) ?: throw AssertionError("expected left page to be available for merging")

                            // TODO: do exact calculation of: page fits in predecessor (use freeindexentries)
                            // leftPage.compactIndexArea()
                            // child.compactIndexArea()

                            if (leftPage.freeSpace() - entryForReplacement.length - leftPage.INDEX_ENTRY_SIZE > (
                                    PAGESIZE - child.freeSpace())) {
                                // should fit
                                entryForReplacement.childPageNumber = null  // must be set by inner page, if it is one
                                // println("1merging ${child.number} into $prevChildPageNo parent: ${page.number}")
                                for (e in child.indexEntries()) {
                                    if (!e.deleted) {
                                        val entry = unmarshallEntry(child, e)
                                        if (entry.isInnerNode && entry.key == EmptyPageEntry()) {
                                            entryForReplacement.childPageNumber = entry.childPageNumber
                                        } else if (leftPage.allocationFitsIntoPage(entry.length)) {
                                            leftPage.add(entry)
                                            child.remove(e)
                                        } else {
                                            throw AssertionError("all should fit into left")
                                        }
                                    }
                                }
                                leftPage.add(entryForReplacement)
                                file.freePage(child)
                            } else {
                                if (!page.allocationFitsIntoPage(entryForReplacement.length)) {
                                    println("doing split")
                                    pageEntries.removeAt(index)
                                    return Pair(true, insertAndSplitIfNecessary(page, entryForReplacement, index, pageEntries, true))
                                } else {
                                    page.add(entryForReplacement)
                                }
                            }

                        } else {
                            if (!page.allocationFitsIntoPage(entryForReplacement.length)) {
                                println("doing split")
                                pageEntries.removeAt(index)
                                return Pair(true, insertAndSplitIfNecessary(page, entryForReplacement, index, pageEntries, true))
                            } else {
                                page.add(entryForReplacement)
                            }
                        }
                    }
                } else {  // remove this value, entry, linkage, ... stay except if merge is necessary
                    page.remove(greater.indexEntry)
                    page.add(greater)
                }
            } else {
                throw IndexOutOfBoundsException("value not found associated with key")
            }
        }
        return Pair(false, null)
    }

    private fun handleEmptyChildPage(page: NioPageFilePage, child: NioPageFilePage, pageEntries: MutableList<NioBTreeEntry>, index: Int, toReInsert: MutableList<NioBTreeEntry>) {
        val referingEntry = pageEntries[index]
        if (index == 0) {
            if (pageEntries.size == 1) {
                page.remove(referingEntry.indexEntry!!)
                // this page is now empty
            } else {
                // in this case delete second entry and reinsert it.
                // the page the second entry pointed to must be refered to by the first
                referingEntry.childPageNumber = pageEntries[1].childPageNumber
                pageEntries[1].childPageNumber = null
                page.remove(pageEntries[1].indexEntry!!)
                page.remove(referingEntry.indexEntry!!)
                page.add(referingEntry)
                toReInsert.add(pageEntries[1])
            }
        } else {
            page.remove(referingEntry.indexEntry!!)
            referingEntry.childPageNumber = null
            toReInsert.add(referingEntry)
        }
        file.freePage(child)
    }

    private fun tryMergeChildToLeftPage(pageEntries: MutableList<NioBTreeEntry>, index: Int, child: NioPageFilePage, page: NioPageFilePage): Boolean {
        val rightEntry = pageEntries[index - 1]
        // child is small, try to add it to the left
        val prevChildPageNo = pageEntries[index - 2].childPageNumber ?: throw AssertionError("expected left page to be available for merging")
        val leftPage = NioPageFilePage(file, prevChildPageNo)

        // TODO: do exact calculation of: page fits in predecessor (use freeindexentries)
        return mergeRightToLeft(leftPage, child, rightEntry, page)
    }

    private fun tryMergeRightToChild(pageEntries: MutableList<NioBTreeEntry>, index: Int, child: NioPageFilePage, page: NioPageFilePage): Boolean {
        val rightEntry = pageEntries[index]
        // leftmost page is small, try to add everything from the right page
        val rightPage = NioPageFilePage(file, rightEntry.childPageNumber!!)
        // TODO: do exact calculation of: page fits in predecessor (use freeindexentries)
        return mergeRightToLeft(child, rightPage, rightEntry, page)
    }

    private fun mergeRightToLeft(leftPage: NioPageFilePage, rightPage: NioPageFilePage, rightEntry: NioBTreeEntry, page: NioPageFilePage): Boolean {
        leftPage.compactIndexArea()
        rightPage.compactIndexArea()

        if (leftPage.freeSpace() - rightEntry.length - leftPage.INDEX_ENTRY_SIZE >
                (PAGESIZE - rightPage.freeSpace())) {
            rightEntry.childPageNumber = null
            // println("2merging ${rightPage.number} into ${leftPage.number} parent: ${page.number}")
            for (e in rightPage.indexEntries()) {
                if (!e.deleted) {
                    val entry = unmarshallEntry(rightPage, e)
                    if (entry.isInnerNode && entry.key == EmptyPageEntry()) {
                        rightEntry.childPageNumber = entry.childPageNumber
                    } else if (leftPage.allocationFitsIntoPage(entry.length)) {
                        leftPage.add(entry)
                        rightPage.remove(e)
                    } else {
                        throw AssertionError("all should fit into left")
                    }
                }
            }
            leftPage.add(rightEntry)
            page.remove(rightEntry.indexEntry!!)
            file.freePage(rightPage)
            return true
        } else {
            return false
        }
    }


    private fun findSmallestEntry(child: NioPageFilePage): NioBTreeEntry {
        val entries = getSortedEntries(child)
        val childPageNumber = entries[0].childPageNumber
        if (childPageNumber != null)
            return findSmallestEntry(NioPageFilePage(file, childPageNumber))
        else
            return entries[0]
    }

    private fun getNextValidIndexEntry(childIndexEntries: Iterator<NioPageFilePage.IndexEntry>): NioPageFilePage.IndexEntry {
        while (childIndexEntries.hasNext()) {
            val e = childIndexEntries.next()
            if (!e.deleted)
                return e
        }

        throw AssertionError("expecting at least one valid entry")
    }

    fun remove(tx: TXIdentifier, key: NioPageEntry, value: NioPageEntry) {
        val toReInsert = mutableListOf<NioBTreeEntry>()
        val splitElement = delete(getPrepareRoot(), key, value, toReInsert).second
        fixRoot(splitElement)
        for (e in toReInsert) {
            insertAndFixRoot(e, true)
        }
        val message = check()
        if (message.length > 0) println(message)
    }

    /*
    fun find(tx: TXIdentifier, entry: NioPageEntry) : Iterator<NioPageEntry> {

    }
    */

    fun iterator(tx: TXIdentifier): Iterator<NioPageEntry> {

        return object : Iterator<NioPageEntry> {
            val path = Stack<Pair<NioPageFilePage, Int>>()

            init {
                val p = Pair(NioPageFilePage(file, root!!.number), 0)
                path.push(p)
            }

            override fun hasNext(): Boolean {
                while (true) {
                    val top = path.pop()
                    val entries = getSortedEntries(top.first)
                    if (top.second < entries.size) {
                        val act = entries[top.second]

                        val childPageNumber = act.childPageNumber
                        if (childPageNumber != null && top.second == 0) {
                            val child = NioPageFilePage(file, childPageNumber)
                            path.push(top)  // restore parent
                            path.push(Pair(child, 0))
                        } else {
                            path.push(top)
                            return true
                        }
                    } else {
                        if (path.size == 0)
                        // reached the top where nothing is left
                            return false
                        else {
                            val parent = path.pop()
                            path.push(Pair(parent.first, parent.second + 1))
                        }
                    }
                }
            }

            override fun next(): NioPageEntry {
                if (hasNext()) {
                    val top = path.pop()
                    val entries = getSortedEntries(top.first)
                    val result = entries[top.second]
                    val childPageNumber = result.childPageNumber
                    if (childPageNumber != null) {
                        val child = NioPageFilePage(file, childPageNumber)
                        path.push(top)
                        path.push(Pair(child, 0))
                    } else {
                        path.push(Pair(top.first, top.second + 1))
                    }
                    return result
                } else {
                    throw IndexOutOfBoundsException("BTree Iterator, next called but nothing left")
                }
            }

        }

    }

    fun check(): String {
        fun checkPage(page: NioPageFilePage, smallerEntry: NioPageEntry, done: MutableSet<Int>, result: StringBuffer) {
            result.append(page.checkDataPage())
            // println("Doing Page: ${page.number}")
            val entries = getSortedEntries(page)
            if (entries.size == 0) {
                result.append("page(${page.number}):Empty Leaf Page\n")
            } else {
                var tmp = entries[0]
                if (tmp.childPageNumber != null) {
                    if (tmp.key != EmptyPageEntry()) {
                        result.append("page(${page.number}):Expected empty first key in page ${page.number}\n")
                    }
                    if (entries[0].values.length != 4.toShort()) {
                        result.append("page(${page.number}):Expected length of 4 in values in first entry of page ${page.number}\n")
                    }
                    if (entries.size == 1) {
                        result.append("page(${page.number}):Empty Inner Page\n")
                    }
                    for (i in 1..entries.size - 1) {
                        val nioBTreeEntry = entries[i]
                        var compareTo = nioBTreeEntry.compareTo(smallerEntry)
                        if (compareTo != 1) {
                            result.append("page(${page.number}):Expected $nioBTreeEntry to be bigger than $smallerEntry but is $compareTo\n")
                        }
                        compareTo = nioBTreeEntry.compareTo(tmp)
                        if (compareTo != 1) {
                            result.append("page(${page.number}):Expected $nioBTreeEntry to be bigger than $tmp but is $compareTo\n")
                        }
                        tmp = nioBTreeEntry
                        if (nioBTreeEntry.key == EmptyPageEntry()) {
                            result.append("page(${page.number}):Invalid entry $nioBTreeEntry may not be EmptyEntry\n")
                        }
                        val childPageNumber = nioBTreeEntry.childPageNumber
                        if (childPageNumber == null) {
                            result.append("page(${page.number}): Invalid entry $nioBTreeEntry no childpage reference\n")
                        }
                    }
                    // result.append("checking childs of ${page.number}\n")
                    for (i in 0..entries.size - 1) {
                        val childPageNumber = entries[i].childPageNumber
                        if (childPageNumber != null) {
                            if (file.freeMap.findDescription(childPageNumber).getUsed(childPageNumber))
                                checkPage(NioPageFilePage(file, childPageNumber), entries[i].key, done, result)
                            else
                                result.append("page(${page.number}): refered childpage: $childPageNumber is marked as free\n")
                        }
                    }
                } else {
                    var tmp = smallerEntry
                    for (i in 0..entries.size - 1) {
                        val nioBTreeEntry = entries[i]
                        val childPageNumber = nioBTreeEntry.childPageNumber
                        if (childPageNumber != null) {
                            result.append("page(${page.number}): Invalid leaf entry $nioBTreeEntry has childpage reference to $childPageNumber\n")
                        }
                        val compareTo = nioBTreeEntry.compareTo(tmp)
                        if (compareTo != 1) {
                            result.append("page(${page.number}): Expected $nioBTreeEntry to be bigger than $tmp but is $compareTo\n")
                        }
                        tmp = nioBTreeEntry

                    }
                }
                if (done.contains(page.number)) {
                    result.append("page(${page.number}): handled more than once\n")
                } else {
                    done.add(page.number)
                }
            }
            // result.append("Ready  Page: ${page.number}\n")
        }

        val result = StringBuffer()
        checkPage(root!!, EmptyPageEntry(), mutableSetOf(), result)
        return result.toString()
    }

}