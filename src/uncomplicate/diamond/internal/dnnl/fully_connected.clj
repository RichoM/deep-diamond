(ns uncomplicate.diamond.internal.dnnl.fully-connected
  (:require [uncomplicate.commons
             [core :refer [Releaseable release let-release with-release]]
             [utils :refer [dragan-says-ex]]]
            [uncomplicate.neanderthal
             [core :refer [axpy! axpby! view zero dim]]
             [real :refer [nrm2 asum]]
             [math :refer [sqr pow sqrt]]
             [vect-math :refer [linear-frac! linear-frac mul! log! log sqrt! sqr!]]
             [block :refer [buffer]]]
            [uncomplicate.neanderthal.internal.api :refer [flow]]
            [uncomplicate.diamond
             [tensor :as tz
              :refer [Transfer input output connector view-tz revert shape layout]]
             [dnn :refer [Parameters bias weights]]]
            [uncomplicate.diamond.internal.protocols
             :refer [BlueprintProvider FactoryProvider DiffParameters
                     diff-bias diff-weights Backprop forward backward]]
            [uncomplicate.diamond.internal.dnnl
             [protocols :refer :all]
             [core :refer :all]
             [tensor :refer [dnnl-tensor dnnl-transformer]]])
  (:import clojure.lang.IFn))

;; ================================ Sum ======================================

(deftype DnnlSum [strm dst sum-prim sum-args]
  Releaseable
  (release [_]
    (release sum-prim))
  IFn
  (invoke [this]
    (execute! strm sum-prim sum-args)
    dst))

(deftype DnnlSumBlueprint [strm sum-pd]
  Releaseable
  (release [_]
    (release sum-pd))
  IFn
  (invoke [this src-and-dst]
    (let-release [sum-prim (primitive sum-pd)]
      (->DnnlSum strm src-and-dst sum-prim (args (buffer src-and-dst)))))
  (invoke [this dst src]
    (let-release [sum-prim (primitive sum-pd)]
      (->DnnlSum strm dst sum-prim (args (buffer dst) (buffer src)))))
  (invoke [this dst src0 src1]
    (let-release [sum-prim (primitive sum-pd)]
      (->DnnlSum strm dst sum-prim
                 (args (buffer dst) (buffer src0) (buffer src1)))))
  (invoke [this dst src0 src1 srcs]
    (let-release [sum-prim (primitive sum-pd)]
      (->DnnlSum strm dst sum-prim
                 (apply args (buffer dst) (buffer src0) (buffer src1) (map buffer srcs))))))

(defn dnnl-sum-blueprint
  ([eng strm scale dst]
   (->DnnlSumBlueprint strm (sum! eng scale dst)))
  ([eng strm dst scale src scale-srcs]
   (->DnnlSumBlueprint strm (apply sum! eng dst scale src scale-srcs))))

;; ================================ Activation =============================================

(deftype DnnlActivationInference [fact strm bluep a-tz
                                  eltw-fwd-prim eltw-fwd-args]
  Releaseable
  (release [_]
    (release eltw-fwd-prim))
  Transfer
  (input [_]
    a-tz)
  (output [_]
    a-tz)
  IFn
  (invoke [_]
    (execute! strm eltw-fwd-prim eltw-fwd-args)
    a-tz))

(deftype DnnlActivationTraining [fact strm bluep z-tz a-tz
                                 eltw-fwd-prim eltw-fwd-args
                                 eltw-bwd-prim eltw-bwd-args]
  Releaseable
  (release [_]
    (release eltw-fwd-prim)
    (release eltw-bwd-prim))
  Transfer
  (input [_]
    z-tz)
  (output [_]
    a-tz)
  IFn
  (invoke [_]
    (execute! strm eltw-fwd-prim eltw-fwd-args)
    a-tz)
  Backprop
  (forward [this]
    (execute! strm eltw-fwd-prim eltw-fwd-args)
    this)
  (backward [this]
    (execute! strm eltw-bwd-prim eltw-bwd-args)
    this))

(deftype DnnlActivationBlueprint [fact eltw-infer-pd eltw-train-pd eltw-bwd-pd]
  Releaseable
  (release [_]
    (release eltw-infer-pd)
    (release eltw-train-pd)
    (release eltw-bwd-pd))
  DescProvider
  (desc [_]
    (dst-md eltw-train-pd))
  IFn
  (invoke [this src-tz]
    (let-release [eltw-fwd-prim (primitive eltw-infer-pd)
                  eltw-fwd-args (eltwise-args (buffer src-tz))]
      (->DnnlActivationInference fact (flow fact) this src-tz
                                 eltw-fwd-prim eltw-fwd-args)))
  (invoke [this src-tz dst-tz]
    (let-release [eltw-fwd-prim (primitive eltw-train-pd)
                  eltw-fwd-args (eltwise-args (buffer src-tz) (buffer dst-tz))
                  eltw-bwd-prim (primitive eltw-bwd-pd)
                  eltw-bwd-args (eltwise-args (buffer src-tz) (buffer dst-tz) (buffer src-tz))]
      (->DnnlActivationTraining fact (flow fact) this src-tz dst-tz
                                eltw-fwd-prim eltw-fwd-args
                                eltw-bwd-prim eltw-bwd-args))))

(defn dnnl-activ-blueprint
  ([fact eng src-desc diff-desc activ alpha beta]
   (let [src-desc (desc src-desc)
         diff-desc (desc diff-desc)]
     (with-release [eltw-infer-desc (eltwise-fwd-desc :inference activ src-desc)
                    eltw-train-desc (eltwise-fwd-desc :training activ src-desc alpha beta)
                    eltw-bwd-desc (eltwise-bwd-desc activ diff-desc src-desc alpha beta)]
       (let-release [eltw-infer-pd (primitive-desc eng eltw-infer-desc)
                     eltw-train-pd (primitive-desc eng eltw-train-desc)
                     eltw-bwd-pd (primitive-desc eng eltw-bwd-desc eltw-train-pd)]
         (->DnnlActivationBlueprint fact eltw-infer-pd eltw-train-pd eltw-bwd-pd))))))

;; ================================ Activation =============================================

(deftype DnnlInnerProductInference [fact strm bluep
                                    src-conn bias-tz weights-tz dst-tz
                                    fc-fwd-prim fc-fwd-args]
  Releaseable
  (release [_]
    (release src-conn)
    (release bias-tz)
    (release weights-tz)
    (release dst-tz)
    (release fc-fwd-prim))
  Transfer
  (input [_]
    (input src-conn))
  (output [_]
    dst-tz)
  Parameters
  (bias [_]
    bias-tz)
  (weights [_]
    weights-tz)
  IFn
  (invoke [_]
    (src-conn)
    (execute! strm fc-fwd-prim fc-fwd-args)
    dst-tz))

(deftype DnnlInnerProductTraining [fact strm bluep
                                   src-conn bias-tz weights-tz dst-tz diff-weights-tz diff-bias-tz
                                   fc-fwd-prim fc-fwd-args
                                   bwd-src-conn diff-dst-conn diff-weights-conn
                                   fc-bwd-weights-prim fc-bwd-weights-args
                                   diff-dst-data-conn weights-conn diff-src-conn
                                   fc-bwd-data-prim fc-bwd-data-args]
  Releaseable
  (release [_]
    (release src-conn)
    (release bias-tz)
    (release weights-tz)
    (release dst-tz)
    (release diff-weights-tz)
    (release diff-bias-tz)
    (release fc-fwd-prim)
    (release bwd-src-conn)
    (release diff-dst-conn)
    (release diff-weights-conn)
    (release fc-bwd-weights-prim)
    (release fc-bwd-weights-args)
    (release diff-dst-data-conn)
    (release weights-conn)
    (release diff-src-conn)
    (release fc-bwd-data-prim))
  Transfer
  (input [_]
    (input src-conn))
  (output [_]
    dst-tz)
  Parameters
  (bias [_]
    bias-tz)
  (weights [_]
    weights-tz)
  DiffParameters
  (diff-bias [_]
    diff-bias-tz)
  (diff-weights [_]
    diff-weights-tz)
  IFn
  (invoke [_]
    (src-conn)
    (execute! strm fc-fwd-prim fc-fwd-args)
    dst-tz)
  Backprop
  (forward [this]
    (src-conn)
    (execute! strm fc-fwd-prim fc-fwd-args)
    this)
  (backward [this]
    (bwd-src-conn)
    (diff-dst-conn)
    (execute! strm fc-bwd-weights-prim fc-bwd-weights-args)
    (diff-weights-conn)
    (when fc-bwd-data-prim
      (diff-dst-data-conn)
      (weights-conn)
      (execute! strm fc-bwd-data-prim fc-bwd-data-args)
      (diff-src-conn))
    this))

(deftype DnnlInnerProductBlueprint [fact bias-desc fc-infer-pd
                                    fc-train-pd fc-bwd-weights-pd fc-bwd-data-pd]
  Releaseable
  (release [_]
    (release bias-desc)
    (release fc-infer-pd)
    (release fc-train-pd)
    (release fc-bwd-weights-pd)
    (release fc-bwd-data-pd))
  DescProvider
  (desc [_]
    (dst-md fc-train-pd))
  IFn
  (invoke [this src-tz]
    (let-release [src-conn (connector src-tz (src-md fc-infer-pd))
                  bias-tz (dnnl-tensor fact bias-desc)
                  weights-tz (dnnl-tensor fact (weights-md fc-infer-pd))
                  dst-tz (dnnl-tensor fact (dst-md fc-infer-pd))
                  fc-fwd-prim (primitive fc-infer-pd)
                  fc-fwd-args (fwd-args (buffer (output src-conn))
                                        (buffer weights-tz) (buffer bias-tz)
                                        (buffer dst-tz))]
      (->DnnlInnerProductInference fact (flow fact) this
                                   src-conn bias-tz weights-tz dst-tz
                                   fc-fwd-prim fc-fwd-args)))
  (invoke [this src-tz dst-tz prop-diff?]
    (let-release [src-conn (connector src-tz (src-md fc-train-pd))
                  bias-tz (dnnl-tensor fact bias-desc)
                  weights-tz (dnnl-tensor fact (weights-md fc-train-pd))
                  dst-conn (connector (dst-md fc-train-pd) dst-tz)
                  fc-fwd-prim (primitive fc-train-pd)
                  fc-fwd-args (fwd-args (buffer (output src-conn))
                                        (buffer weights-tz) (buffer bias-tz)
                                        (buffer (input dst-tz)))
                  bwd-src-conn (connector src-conn (src-md fc-bwd-weights-pd))
                  diff-dst-conn (connector dst-tz (diff-dst-md fc-bwd-weights-pd))
                  diff-weights-tz (dnnl-tensor fact (weights-md fc-train-pd))
                  diff-weights-conn (connector (diff-weights-md fc-bwd-weights-pd)
                                               diff-weights-tz)
                  diff-bias-tz (dnnl-tensor fact bias-desc)
                  fc-bwd-weights-prim (primitive fc-bwd-weights-pd)
                  fc-bwd-weights-args (bwd-args (buffer (output bwd-src-conn))
                                                (buffer (output diff-dst-conn))
                                                (buffer (input diff-weights-conn))
                                                (buffer diff-bias-tz))]
      (if prop-diff?
        (let-release [diff-dst-data-conn (connector diff-dst-conn (diff-dst-md fc-bwd-data-pd))
                      weights-conn (connector weights-tz (weights-md fc-bwd-data-pd))
                      diff-src-conn (connector (diff-src-md fc-bwd-data-pd) src-conn)
                      fc-bwd-data-prim (primitive fc-bwd-data-pd)
                      fc-bwd-data-args (bwd-args (buffer (output diff-dst-data-conn))
                                                 (buffer (output weights-conn))
                                                 (buffer (input diff-src-conn)))]
          (->DnnlInnerProductTraining fact (flow fact) this
                                      src-conn bias-tz weights-tz dst-tz diff-weights-tz diff-bias-tz
                                      fc-fwd-prim fc-fwd-args
                                      bwd-src-conn diff-dst-conn diff-weights-conn
                                      fc-bwd-weights-prim fc-bwd-weights-args
                                      diff-dst-data-conn weights-conn diff-src-conn
                                      fc-bwd-data-prim fc-bwd-data-args))
        (->DnnlInnerProductTraining fact (flow fact) this
                                    src-conn bias-tz weights-tz dst-tz diff-weights-tz diff-bias-tz
                                    fc-fwd-prim fc-fwd-args
                                    bwd-src-conn diff-dst-conn diff-weights-conn
                                    fc-bwd-weights-prim fc-bwd-weights-args
                                    nil nil nil nil nil)))))

(defn dnnl-inner-product-blueprint
  ([fact eng src-desc dst-desc weights-type]
   (let [dst-desc (desc dst-desc)
         dst-shape (dims dst-desc)
         dst-type (data-type dst-desc)
         bias-shape [(dst-shape 1)]
         weights-shape (vec (cons (dst-shape 1) (drop 1 (dims src-desc))))
         weights-type (or weights-type dst-type)
         src-desc (desc src-desc)]
     (let-release [bias-desc (memory-desc bias-shape dst-type :x)]
       (with-release [weights-desc (memory-desc weights-shape weights-type :any)
                      fc-infer-desc (inner-product-fwd-desc :inference src-desc weights-desc
                                                            bias-desc dst-desc)
                      fc-train-desc (inner-product-fwd-desc :training src-desc weights-desc
                                                            bias-desc dst-desc)
                      fc-bwd-weights-desc (inner-product-bwd-desc src-desc weights-desc
                                                                  bias-desc dst-desc)
                      fc-bwd-data-desc (inner-product-bwd-desc src-desc weights-desc dst-desc)] ;;TODO the order of args is different here and in bwd-args
         (let-release [fc-infer-pd (primitive-desc eng fc-infer-desc)
                       fc-train-pd (primitive-desc eng fc-train-desc)
                       fc-bwd-weights-pd (primitive-desc eng fc-bwd-weights-desc fc-train-pd)
                       fc-bwd-data-pd (primitive-desc eng fc-bwd-data-desc fc-train-pd)]
           (->DnnlInnerProductBlueprint fact bias-desc fc-infer-pd
                                        fc-train-pd fc-bwd-weights-pd fc-bwd-data-pd))))))
  ([fact eng src-desc dst-desc]
   (dnnl-inner-product-blueprint fact eng src-desc dst-desc nil)))

;; ================================ Fully Connected Layer ==================================

(deftype FullyConnectedInference [fact bluep ip activ]
  Releaseable
  (release [_]
    (release ip)
    (release activ))
  FactoryProvider
  (factory [_]
    fact)
  BlueprintProvider
  (blueprint [_]
    bluep)
  Parameters
  (bias [_]
    (bias ip))
  (weights [_]
    (weights ip))
  Transfer
  (input [this]
    (input ip))
  (output [_]
    (output activ))
  IFn
  (invoke [_]
    (ip)
    (activ)))

(deftype FullyConnectedSGD [fact bluep ip activ ^long n
                            v w diff-weights-vec
                            b diff-bias-vec]
  Releaseable
  (release [_]
    (release ip)
    (release activ)
    (release v))
  FactoryProvider
  (factory [_]
    fact)
  BlueprintProvider
  (blueprint [_]
    bluep)
  Transfer
  (input [this]
    (input ip))
  (output [_]
    (output activ))
  Parameters
  (weights [_]
    (weights ip))
  (bias [_]
    (bias ip))
  IFn
  (invoke [_]
    (ip)
    (activ))
  Backprop
  (forward [this]
    (forward activ)
    this)
  (forward [this [_ _ mu nesterov?]]
    (when nesterov? (axpy! mu v w))
    (forward ip)
    (forward activ)
    this)
  (backward [this]
    (backward activ)
    this)
  (backward [this [eta lambda mu nesterov?]]
    (let [eta-avg (- (/ (double eta) n))]
      (when nesterov? (axpy! (- (double mu)) v w))
      (backward ip)
      (axpby! eta-avg diff-weights-vec mu v)
      (axpy! eta-avg diff-bias-vec b)
      (axpby! 1.0 v (inc (* eta-avg (double lambda))) w)
      this)))

(defn sgd-layer [fact bluep ip activ n]
  (let [w (view (weights ip))
        v (zero w)]
    (->FullyConnectedSGD fact bluep ip activ n
                         v w (view (diff-weights ip))
                         (view (bias ip)) (view (diff-bias ip)))))

(deftype FullyConnectedAdam [fact bluep ip activ ^long n
                             s r w g
                             b diff-bias-vec]
  Releaseable
  (release [_]
    (release ip)
    (release activ)
    (release s)
    (release r)
    (release w))
  FactoryProvider
  (factory [_]
    fact)
  BlueprintProvider
  (blueprint [_]
    bluep)
  Transfer
  (input [this]
    (input ip))
  (output [_]
    (output activ))
  Parameters
  (weights [_]
    (weights ip))
  (bias [_]
    (bias ip))
  IFn
  (invoke [_]
    (ip)
    (activ))
  Backprop
  (forward [this]
    (forward activ)
    this)
  (forward [this _]
    (forward ip)
    (forward activ)
    this)
  (backward [this]
    (backward activ)
    this)
  (backward [this [t eta lambda rho1 rho2 epsilon]]
    (let [t (swap! t inc)
          eta (double (or eta 0.001))
          lambda (double (or lambda 0.0))
          rho1 (double (or rho1 0.9))
          rho2 (double (or rho2 0.999))
          epsilon (double (or epsilon 1e-6))
          eta-avg (- (/ (double eta) n))]
      (backward ip)
      (axpby! (- 1.0 rho1) g rho1 s)
      (axpby! (- 1.0 rho2) (sqr! g) rho2 r)
      (linear-frac! (/ (- eta) (- 1.0 (pow rho1 t))) s 0.0
                    (/ 1.0 (sqrt (- 1.0 (pow rho2 t)))) (sqrt! r g) epsilon g)
      (axpy! eta-avg diff-bias-vec b)
      (axpby! 1.0 g (inc (* eta-avg lambda)) w)
      this)))

(defn adam-layer [fact bluep ip activ n]
  (let-release [w (view (weights ip))
                s (zero w)
                r (zero w)]
    (->FullyConnectedAdam fact bluep ip activ n s r w (view (diff-weights ip))
                          (view (bias ip)) (view (diff-bias ip)))))

(deftype FullyConnectedBlueprint [fact ip-bluep activ-bluep dst-desc]
  Releaseable
  (release [_]
    (release ip-bluep)
    (release activ-bluep))
  FactoryProvider
  (factory [_]
    fact)
  BlueprintProvider
  (blueprint [this]
    this)
  DescProvider
  (desc [_]
    (desc activ-bluep))
  IFn
  (invoke [this prev-layer]
    (let-release [src-tz (output prev-layer)
                  ip (ip-bluep src-tz)
                  activ (activ-bluep (output ip))]
      (->FullyConnectedInference fact this ip activ)))
  (invoke [this prev-layer prop-diff? optimization]
    (let-release [src-tz (output prev-layer)
                  z-tz (dnnl-tensor fact dst-desc)
                  a-tz (dnnl-tensor fact dst-desc)
                  ip (ip-bluep src-tz z-tz prop-diff?)
                  activ (activ-bluep z-tz a-tz)]
      (let [n ((shape src-tz) 0)
            training-layer (case optimization
                             :sgd sgd-layer
                             :adam adam-layer
                             (dragan-says-ex
                              "This optimization algorithm is not available for MKL-DNN backend."
                              {:optimization optimization}))]
        (training-layer fact this ip activ n))))
  (invoke [this prev-layer prop-diff?]
    (.invoke this prev-layer prop-diff? :sgd)))

(defn dnnl-fc-blueprint [fact eng src-desc dst-desc activ alpha beta weights-type]
  (let-release [dst-desc (memory-desc (shape dst-desc)
                                      (or (tz/data-type dst-desc) (data-type src-desc))
                                      :nc)
                ip-bluep (dnnl-inner-product-blueprint fact eng src-desc dst-desc
                                                       (or weights-type (data-type src-desc)))
                activ-bluep (dnnl-activ-blueprint fact eng dst-desc dst-desc activ alpha beta)]
    (->FullyConnectedBlueprint fact ip-bluep activ-bluep dst-desc)))

(deftype UniversalCost [strm prev-layer
                        sum-prim sum-args
                        connect-output connect-diff
                        a-y cost]
  Releaseable
  (release [_]
    (release connect-output)
    (release connect-diff))
  Transfer
  (input [this]
    (input connect-output))
  (output [_]
    (output connect-output))
  Backprop
  (forward [this]
    (connect-output)
    this)
  (backward [this]
    (execute! strm sum-prim sum-args)
    (connect-diff)
    (backward prev-layer)
    this)
  IFn
  (invoke [_]
    (connect-output)
    (execute! strm sum-prim sum-args)
    (cost a-y)))

(defn dnnl-universal-cost [eng strm prev-layer train-tz cost]
  (let [train-desc (desc train-tz)
        output-desc (memory-desc (dims (output prev-layer))
                                 (data-type train-desc) (strides train-desc))]
    (let-release [connect-output (connector (output prev-layer) output-desc)
                  connect-diff (revert connect-output)]
      (with-release [sum-desc (sum! eng output-desc 1.0 output-desc -1.0 train-tz)]
        (let-release [sum-prim (primitive sum-desc)]
          (->UniversalCost strm prev-layer sum-prim
                           (args (buffer (input connect-diff)) (buffer (input connect-diff))
                                 (buffer train-tz))
                           connect-output connect-diff
                           (view (output connect-output))
                           cost))))))

(defn quadratic-cost [a-y]
  (/ (sqr (nrm2 a-y)) (* 2 (dim a-y))))

(defn mean-absolute-cost [a-y]
  (/ (asum a-y) (dim a-y)))

(defn sigmoid-crossentropy-cost [^long n a y]
  (with-release [ylna (mul! (log a) y)
                 y-1 (linear-frac 1.0 y -1.0)]
    (/ (asum (axpy! -1.0 ylna (mul! y-1 (log! (linear-frac! -1.0 a 1.0))))) n)))

(deftype CustomCost [strm prev-layer
                     sum-prim sum-args
                     connect-output connect-diff
                     a y cost]
  Releaseable
  (release [_]
    (release connect-output)
    (release connect-diff))
  Transfer
  (input [this]
    (input connect-output))
  (output [_]
    (output connect-output))
  Backprop
  (forward [this]
    (connect-output)
    this)
  (backward [this]
    (execute! strm sum-prim sum-args)
    (connect-diff)
    (backward prev-layer)
    this)
  IFn
  (invoke [_]
    (connect-output)
    (cost a y)))

(defn dnnl-custom-cost [eng strm prev-layer train-tz cost]
  (let [train-desc (desc train-tz)
        output-desc (memory-desc (dims (output prev-layer))
                                 (data-type train-desc) (strides train-desc))]
    (let-release [connect-output (connector (output prev-layer) output-desc)
                  connect-diff (revert connect-output)]
      (with-release [sum-desc (sum! eng output-desc 1.0 output-desc -1.0 train-tz)]
        (let-release [sum-prim (primitive sum-desc)]
          (->CustomCost strm prev-layer sum-prim
                        (args (buffer (input connect-diff)) (buffer (input connect-diff))
                              (buffer train-tz))
                        connect-output connect-diff
                        (view (output connect-output))
                        (view train-tz)
                        cost))))));;TODO see about offsets
;; maybe leave output as-is and always copy the subtensor back from output for the computation?


;; TODO create input layer that can handle subvectors
;; it seems that connector already takes into account memory shift, if it exists! good! NO! it does not provide mem!
;; just implement shift in the first layer! (implemented: offset! does this now.)

;; create output layer that can handle subvectors (done!)

;; diff reorder goes into the opposite direction, and reuses a to store aL-y!
