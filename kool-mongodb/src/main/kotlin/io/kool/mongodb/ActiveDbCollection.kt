package io.kool.mongodb

import com.mongodb.BasicDBObject
import com.mongodb.DBCollection
import com.mongodb.DBObject
import io.kool.stream.support.AbstractHandler
import java.util.ArrayList
import java.util.Collection
import java.util.HashMap
import java.util.List
import java.util.ListIterator
import java.util.Map
import java.util.concurrent.atomic.AtomicBoolean
import java.util.LinkedHashMap

/**
* Returns the primary key of the given database object
*/
val DBObject.id: Any?
    get() = this["_id"]

/**
 * Represents an active collection which is kept to date using replication events
 */
public class ActiveDbCollection(val dbCollection: DBCollection, val query: DBObject? = null): Collection<DBObject> {
    private var _idMap: Map<Any?, DBObject> = HashMap<Any?, DBObject>()

    val handler = ActiveDbCollectionHandler(this)
    var loaded = AtomicBoolean(false)

    public override fun toString(): String = "ActiveDbCollection($dbCollection, $query)"

    public override fun equals(o: Any?): Boolean {
        return if (o is ActiveDbCollection) {
            this.dbCollection == o.dbCollection && this.query == o.query
        } else false
    }

    public override fun hashCode(): Int {
        var answer = 31 * dbCollection.hashCode()
        if (query != null) {
            answer += query.hashCode()
        }
        return answer;
    }

    protected val collection: Collection<DBObject>
        get() {
            return idMap.values()
        }

    protected val idMap: Map<Any?, DBObject>
        get() {
            checkLoaded()
            return _idMap
        }

    public fun isOpened(): Boolean = handler.isOpen()

    public fun isClosed(): Boolean = handler.isClosed()

    public fun onReplicaEvent(event: ReplicaEvent) {
        // we can discard events before we've loaded
        println("Got a change event $event")
        if (loaded.get()) {
            val change = event.change
            val id = change.get("_id")
            if (id != null) {
                sync {
                    val old = _idMap.get(id)
                    if (event.isDelete()) {
                        if (old != null) {
                            _idMap.remove(id)
                        }
                    } else {
                        if (old != null) {
                            // lets process an update
                            val newValue = old.merge(change)
                            _idMap.put(id, newValue)
                        } else {
                            _idMap.put(id, change)
                        }
                    }
                }
            }
        }
    }

    protected fun checkLoaded() {
        if (loaded.compareAndSet(false, true)) {
            val cursor = if (query != null) {
                dbCollection.find(query)
            } else {
                dbCollection.find()
            }
            val newMap = LinkedHashMap<Any?, DBObject>()
            if (cursor != null) {
                for (e in cursor) {
                    val id = e.id
                    newMap[id] = e
                }
            }
            sync {
                _idMap = newMap
            }
        }
    }

    public fun flush() {
        sync {
            _idMap = HashMap<Any?, DBObject>()
            loaded.set(false)
        }
    }

    /**
     * Strategy function to perform synchronization around the _list and _idMap
     */
    protected fun <T> sync(block: () -> T): T {
        return synchronized (handler, block)
    }

    public fun get(key: Any?): DBObject? {
        return idMap.get(key)
    }

    // Collection API
    public override fun <T: Any?> toArray(a: Array<T>): Array<T> = collection.toArray(a)

    public override fun toArray(): Array<Any?> = collection.toArray()

    public override fun add(element: DBObject): Boolean {
        var answer = false
        val result = dbCollection.save(element)
        val id = result?.getField("_id") ?: element.id
        val old = idMap.get(id)
        if (old != null) {
            collection.remove(old)
        } else {
            answer = true
        }
        collection.add(element)
        idMap.put(id, element)
        return answer
    }

    public override fun addAll(c: Collection<out DBObject>): Boolean {
        var answer = false
        for (element in c) {
            answer = answer || add(element)
        }
        return answer
    }

    public override fun clear() {
        dbCollection.drop()
        flush()
    }
    public override fun contains(o: Any?): Boolean {
        throw UnsupportedOperationException()
    }

    public override fun containsAll(c: Collection<out Any?>): Boolean {
        throw UnsupportedOperationException()
    }

    public override fun isEmpty(): Boolean = collection.isEmpty()

    public override fun iterator(): java.util.Iterator<DBObject> = collection.iterator()

    public override fun remove(element: Any?): Boolean {
        if (element is DBObject) {
            val id = element.id
            val old = idMap.remove(id)
            if (old != null) {
                dbCollection.remove(element)
                collection.remove(element)
                return true
            }
        }
        return false
    }

    public override fun removeAll(c: Collection<out Any?>): Boolean {
        var answer = false
        for (element in c) {
            answer = answer || remove(element)
        }
        return answer
    }

    public override fun retainAll(c: Collection<out Any?>): Boolean {
        throw UnsupportedOperationException()
    }

    public override fun size(): Int = collection.size()
}


class ActiveDbCollectionHandler(val activeCollection: ActiveDbCollection): AbstractHandler<ReplicaEvent>() {

    public override fun toString(): String = "ActiveDbCollectionHandler($activeCollection)"

    public override fun onNext(next: ReplicaEvent) {
        activeCollection.onReplicaEvent(next)
    }

}