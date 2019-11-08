(ns uncomplicate.diamond.internal.dnnl
  (:require [uncomplicate.commons
             [core :refer [let-release with-release]]
             [utils :refer [enc-keyword direct-buffer capacity dragan-says-ex mask]]]
            [uncomplicate.diamond.internal.dnnl
             [impl :refer :all]
             [constants :refer :all]
             [protocols :refer :all]])
  (:import org.bytedeco.javacpp.Pointer
           org.bytedeco.dnnl.global.dnnl
           [org.bytedeco.dnnl dnnl_engine dnnl_memory_desc_t]))

;; ===================== Engine ===============================================

(defn engine
  "Creates an engine for the device `id` of the specified keyword `kind`.

   Supported engine kinds are `:cpu`, `:gpu`, and `:any`. The default kind is `:cpu`.
   Engine has to be `release`d.

  Throws an ExceptionInfo if the `id` does not correspond to a physical device
  or if `kind` is not supported."
  ([^long id kind]
   (wrap (engine* id (enc-keyword dnnl-engine-kind kind))))
  ([^long id]
   (wrap (engine* id)))
  ([]
   (engine 0)))

(defn engine-count
  "Returns the number of physical engines of the specified `kind` (`:cpu`, `:gpu`, `:any`).

  Throws an ExceptionInfo if `kind` is not supported."
  (^long []
   (engine-count*))
  (^long [kind]
   (engine-count* (enc-keyword dnnl-engine-kind kind))))

(defn engine-kind
  "Returns the kind of an engine as a keyword. Typical values are `:gpu` and `:cpu`.

  Throws an ExceptionInfo if `kind` is not supported."
  ([eng]
   (dec-engine-kind (engine-kind* (extract eng)))))

;; ===================== Stream ===============================================

(defn stream
  "Creates a stream for executing primitive operations for engine `eng`.

  Stream execution can be further specified by `flags`, defined in the
  [[constants/dnnl-stream-flags]].
  Stream has to be `release`d."
  [eng & flags]
  (wrap (if flags
          (stream* (extract eng) (mask dnnl-stream-flags flags))
          (stream* (extract eng)))))

(defn wait!
  "Waits until stream `s` completes execution of all queued operations."
  [strm]
  (wait* (extract strm))
  strm)

(defn execute!
  "Queues the operation primitive `p` for execution in stream `strm`.

  Returns `strm`. Throws an ExceptionInfo if the DNNL stream is not valid,
  or the primitive cannot be executed."
  [strm p args]
  (execute* (extract strm) (extract p) args)
  strm)


;; ===================== Memory ===============================================

(defn memory-desc
  "Creates an engine-agnostic, logical, description of data, based on dimensions,
  data type and data format.

  `dims` is a Clojure vector of positive numbers representing dimensions in
  `:abcdef` format, regardless of the physical layout of dimensions.
  `data-type` is a keyword that specifies one of the supported types of data,
  defined in [[`constants/dnnl-data-type`]] (`:float`, `:int`, etc.)
  `format` specifies an (optional) physical layout as a keyword, choosing one
  of [[`constants/dnnl-format`]] (`:nchw`, `:acdeb`, `:any`, etc.), or through
  strides specified as a Clojure vector of positive numbers that match logical
  dimensions.

  Examples:

  (memory-desc [2 3] :float :nc)

  (memory-desc [2 3 4 5] :float [120 3 4 5])
  "
  ([dims data-type format]
   (memory-desc* (if (keyword? format)
                   (enc-keyword dnnl-format format)
                   (long-array format))
                 (long-array dims) (enc-keyword dnnl-data-type data-type)))
  ([dims format]
   (memory-desc dims :float format))
  ([dims]
   (memory-desc dims :float :any)))

(defn submemory-desc
  "TODO"
  ([parent-desc dims offsets]
   (submemory-desc* (desc parent-desc) (long-array dims) (long-array offsets)))
  ([parent-desc dim-a]
   (submemory-desc* (desc parent-desc) dim-a)))

(defn equal-desc?
  "Compares two memory descriptors for logical equality.

  Two descriptors may be equal even though the objects are not
  equal nor identical in the JVM sense.
  "
  [x y]
  (let [x (desc x)
        y (desc y)]
    (or (= x y) (= 1 (dnnl/dnnl_memory_desc_equal x y)))))

(defn data-type
  "Queries the data type of a memory descriptor"
  [mem-desc]
  (dec-data-type (data-type* (desc mem-desc))))

(defn ndims
  "Queries the number of dimensions of a memory descriptor"
  ^long [mem-desc]
  (.ndims ^dnnl_memory_desc_t (desc mem-desc)))

(defn dims
  "Queries the dimensions of a memory descriptor"
  [mem-desc]
  (vec (dims* (desc mem-desc))))

(defn size
  "Queries the mem-desc for its dimensions."
  ^long [mem-desc]
  (dnnl/dnnl_memory_desc_get_size (desc mem-desc)))

(defn strides
  "Queries the strides of a memory descriptor."
  [mem-desc]
  (vec (strides* (desc mem-desc))))

(defn memory
  "An engine-specific memory handle for a raw buffer and a matching descriptor.

  `eng` a DNNL engine that controls the context.
  `mem-desc` logical memory descriptor.
  `buf` Java's DirectByteBuffer instance.
  `marter` indicates whether this memory object handles the life cycle of `buf`."
  ([eng mem-desc buf master]
   (if (<= (size (desc mem-desc)) (capacity buf))
     (memory* (desc mem-desc) (extract eng) buf master)
     (dragan-says-ex "The buffer has to be large enough for mem-desc"
                     {:size (size (desc mem-desc)) :capacity (capacity buf)})))
  ([eng mem-desc buf]
   (memory eng mem-desc buf false))
  ([eng mem-desc]
   (let-release [buf (direct-buffer (size (desc mem-desc)))]
     (memory* (desc mem-desc) (extract eng) buf true))))

(defn offset!
  "Sets the starting position in the buffer that the memory object `mem` controls."
  [mem ^long n]
  (let [p (ptr mem)]
    (if (and (<= 0 n) (<= n (.capacity ^Pointer p)))
      (with-check (dnnl/dnnl_memory_set_data_handle
                   (extract mem) (.position ^Pointer p n))
        mem)
      (dragan-says-ex "There is not enough capacity in the underlying buffer for this offset."
                      {:n n :requested n :available (.capacity ^Pointer p)}))))

(defn get-engine
  "Returns the engine context of the memory object `mem`."
  [mem]
  (wrap (get-engine* (extract mem))))

;; ===================== Desc =================================================

(defn primitive-kind
  "TODO"
  [desc]
  (dec-primitive-kind (primitive-kind* desc)))

(defn primitive-desc
  "TODO"
  ([eng desc]
   (wrap (primitive-desc* desc (extract eng))))
  ([eng desc hint-pd]
   (wrap (primitive-desc* desc (extract eng) (extract hint-pd))))
  ([eng desc hint-pd attr]
   (wrap (primitive-desc* desc (extract eng) (extract hint-pd) (extract attr)))))

;; ===================== Primitive ============================================

(defn primitive
  "TODO"
  [pd]
  (wrap (primitive* (extract pd))))

;; =================== Query ====================================================

(defn src-md
  "Queries the primitive descriptor `pd` for the reference of its source."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_src_md))

(defn diff-src-md
  "Queries the primitive descriptor `pd` for the reference of the gradient
  of its source."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_diff_src_md))

(defn weights-md
  "Queries the primitive descriptor `pd` for the reference of its weights."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_weights_md))

(defn diff-weights-md
  "Queries the primitive descriptor `pd` for the reference of the gradient
  of its weights."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_diff_weights_md))

(defn dst-md
  "Queries the primitive descriptor `pd` for the reference of its destination."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_dst_md))

(defn diff-dst-md
  "Queries the primitive descriptor `pd` for the reference of the gradient
  of its destination."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_diff_dst_md))

(defn workspace-md
  "Queries the primitive descriptor `pd` for the reference of its workspace."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_workspace_md))
