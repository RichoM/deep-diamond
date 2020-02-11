;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns uncomplicate.diamond.internal.cudnn.core-test
  (:require [midje.sweet :refer [facts throws => roughly just]]
            [uncomplicate.commons
             [core :refer [with-release]]
             [utils :refer [capacity direct-buffer put-float get-float]]]
            [uncomplicate.clojurecuda.core
             :refer [with-default default-stream mem-alloc memcpy-host! synchronize!]]
            [uncomplicate.diamond.internal.cudnn
             [core :refer :all]
             [protocols :as api]]))

(with-default
  (with-release [cudnn-hdl (cudnn-handle default-stream)
                 relu-desc (activation-descriptor :relu true 42.0)
                 desc-x (tensor-descriptor [2 3 4 5] :float :nchw)
                 host-x (float-array (range -2 70))
                 gpu-x (mem-alloc (size desc-x))
                 gpu-dx (mem-alloc (size desc-x))
                 host-y (float-array (range 120))
                 gpu-y (mem-alloc (size desc-x))
                 host-dx (float-array (range -6 6 0.1))
                 gpu-dx (mem-alloc (size desc-x))
                 host-dy (float-array (range -0.6 6 0.01))
                 gpu-dy (mem-alloc (size desc-x))]

    (facts "ReLU Activation descriptor."
           (get-activation-descriptor relu-desc) => {:mode :relu :relu-nan-opt true :coef 42.0})

    (memcpy-host! host-x gpu-x)
    (memcpy-host! host-y gpu-y)

    (facts "Activation forward ReLU operation."
           (activation-forward cudnn-hdl relu-desc (float 3.0) desc-x gpu-x (float 2.0) desc-x gpu-y)
           => cudnn-hdl
           (memcpy-host! gpu-x host-x)
           (memcpy-host! gpu-y host-y)
           (take 5 host-x) => [-2.0 -1.0 0.0 1.0 2.0]
           (take 5 host-y) => [0.0 2.0 4.0 9.0 14.0])

    (facts "Activation backward ReLU operation."
           (memcpy-host! host-dx gpu-dx)
           (memcpy-host! host-dy gpu-dy)
           (activation-backward cudnn-hdl relu-desc (float 300.0) desc-x gpu-y desc-x gpu-dy
                                desc-x gpu-x (float 200.0) desc-x gpu-dx)
           => cudnn-hdl
           (memcpy-host! gpu-x host-x)
           (memcpy-host! gpu-y host-y)
           (memcpy-host! gpu-dx host-dx)
           (memcpy-host! gpu-dy host-dy)
           (take 5 host-x) => [-2.0 -1.0 0.0 1.0 2.0]
           (take 5 host-y) => [0.0 2.0 4.0 9.0 14.0]
           (take 5 host-dx) => [-1200.0 -1180.0 -1160.0 -1311.0 -1288.0]
           (take 5 host-dy) => (just [(roughly -0.6) (roughly -0.59) (roughly -0.58)
                                      (roughly -0.57) (roughly -0.56)]))))
