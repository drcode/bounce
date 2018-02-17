(ns bounce.parsers
  (:require [qlkit.core :refer [parse-children parse-children-remote parse-children-sync]]
            [fbc-utils.debug :refer-macros [??]]))

(defmulti read (fn [qterm & _] (first qterm)))

(def ball-radius (/ (* 600 4) 100 2))

(defn dragged-ball-coords [{:keys [:current-ball/drag-start :current-ball/drag-end :ball/by-time :bounce/current-time] :as state}]
  (let [[x y :as coords] (by-time current-time)]
    (if (and drag-start drag-end)
      [(min (max (+ x (first drag-end) (- (first drag-start))) ball-radius) (- 600 ball-radius)) (min (max (+ y (second drag-end) (- (second drag-start))) ball-radius) (- 400 ball-radius))]
      coords)))

(defmethod read :ball/current
  [[dispatch-key params :as query-term] env state]
  (dragged-ball-coords state))

(defmethod read :bounce/current-time
  [[dispatch-key params :as query-term] env {:keys [:bounce/current-time] :as state}]
  current-time)

(defmethod read :bounce/balls
  [[dispatch-key params :as query-term] env {:keys [:ball/by-time] :as state}]
  by-time)

(defmulti mutate (fn [qterm & _] (first qterm)))

(defmethod mutate :current-ball/drag-start!
  [[_ params :as query-term] env state-atom]
  (swap! state-atom 
         (fn [{:keys [:bounce/current-time :ball/by-time] :as state}]
           (let [[x y :as coords] (by-time current-time)
                 {:keys [value]}  params
                 [xnew ynew]      value]
             (if (and coords (<= (+ (* (- x xnew) (- x xnew)) (* (- y ynew) (- y ynew))) 150))
               (assoc state :current-ball/drag-start value)
               state)))))

(defmethod mutate :current-ball/drag-end!
  [query-term env state-atom]
  (swap! state-atom
         (fn [{:keys [:current-ball/drag-start] :as state}]
           (if drag-start
             (assoc state :current-ball/drag-end (:value (second query-term)))
             state))))

(defmethod mutate :current-ball/move!
  [query-term env state-atom]
  (swap! state-atom
         (fn [{:keys [:bounce/current-time :current-ball/drag-start :current-ball/drag-end] :as state}]
           (if (and drag-start drag-end)
             (-> state
                 (dissoc :current-ball/drag-start)
                 (dissoc :current-ball/drag-end)
                 (assoc-in [:ball/by-time current-time] (dragged-ball-coords state)))
             state))))

(defmethod mutate :current-ball/move-cancel!
  [query-term env state-atom]
  (swap! state-atom
         (fn [state]
           (-> state
               (dissoc :current-ball/drag-start)
               (dissoc :current-ball/drag-end)))))

(defmethod mutate :bounce/current-time!
  [[_ params :as query-term] env state-atom]
  (swap! state-atom assoc :bounce/current-time (:value params)))

(defmethod mutate :bounce/new-keyframe!
  [[_ params :as query-term] env state-atom]
  (swap! state-atom
         (fn [{:keys [:bounce/current-time] :as state}]
           (assoc-in state [:ball/by-time current-time] [300 200]))))

(defmethod mutate :bounce/delete-keyframe!
  [[_ params :as query-term] env state-atom]
  (swap! state-atom
         (fn [{:keys [:bounce/current-time] :as state}]
           (update state :ball/by-time dissoc current-time))))

