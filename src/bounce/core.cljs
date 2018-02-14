(ns bounce.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [qlkit.core :as ql]
            [qlkit-renderer.core :refer [transact! update-state!] :refer-macros [defcomponent] :as qr]
            [goog.dom :refer [getElement]]
            [bounce.parsers :refer [read mutate]]
            [qlkit-material-ui.core :refer [enable-material-ui!]]
            [cljs-http.client :as http :refer [post]]
            [cljs.reader :refer [read-string]]
            [fbc-utils.debug :refer-macros [??]]))

(enable-console-print!)
(enable-material-ui!)

(defonce app-state (atom {:ball/by-time        {0 [300 300]
                                                4 [250 200]}
                          :bounce/current-time 0}))

(def gravity 8)

(def subframes 3)

(defn sqr [x]
  (* x x))

(defn sqrt [x]
  (js/Math.sqrt x))

(defn hit [{:keys [x y vx vy] :as arc} [xtarget ytarget]]
  (let [x             (- xtarget x)
        y             (- ytarget y)
        [vx vy]       [(* 0.5 vx)
                       (* 0.5 vy)]
        speed-squared (+ (sqr vx) (sqr vy))
        speed         (sqrt speed-squared)
        inner-part    (- (sqr speed-squared) (* gravity (+ (* gravity (sqr x)) (* 2 y speed-squared))))
        [vx vy]       (cond (pos? inner-part) (let [m            (sqrt inner-part)
                                                    cur-ang      (js/Math.atan2 vy vx)
                                                    theta        (js/Math.atan2 (- speed-squared m) (* gravity x))
                                                    theta-alt    (js/Math.atan2 (+ speed-squared m) (* gravity x))
                                                    vx-nu        (* speed (js/Math.cos theta))
                                                    vy-nu        (* speed (js/Math.sin theta))
                                                    vx-alt       (* speed (js/Math.cos theta-alt))
                                                    vy-alt       (* speed (js/Math.sin theta-alt))
                                                    ang-dist     (js/Math.abs (js/Math.atan2 (js/Math.sin (- cur-ang theta)) (js/Math.cos (- cur-ang theta))))
                                                    ang-dist-alt (js/Math.abs (js/Math.atan2 (js/Math.sin (- cur-ang theta-alt)) (js/Math.cos (- cur-ang theta-alt))))]
                                                (if (< ang-dist ang-dist-alt)
                                                  [vx-nu vy-nu]
                                                  [vx-alt vy-alt]))
                            (pos? y)     (let [theta (js/Math.atan2 (* 2 y) x)
                                               v  (sqrt (/ (* y 2 gravity) (sqr (js/Math.sin theta))))]
                                           [(* v (js/Math.cos theta)) (* v (js/Math.sin theta))])
                            :else             (let [t  (/ (sqrt (* 2 (- y))) (sqrt gravity))
                                                    vx (/ x t)]
                                                [vx 0]))]
    (assoc arc
           :vx vx
           :vy vy)))

(defn animate-ball [this]
  (binding [qr/*this* this]
    (update-state! (fn [{:keys [arc animation-time balls] :as state}]
                     (let [{:keys [arc] :as state}        (cond-> state
                                                            (not arc) (assoc :arc
                                                                                          {:start-time 0
                                                                                           :x          100
                                                                                           :y          0
                                                                                           :vx         60
                                                                                           :vy         20}))
                           key-time                       (/ animation-time subframes)
                           {:keys [start-time x y vx vy]} arc
                           tim (mod (+ 100 (- (/ animation-time subframes) start-time)) 100)
                           x (+ x (* vx tim))
                           y (+ y (* vy tim) (- (* 0.5 gravity (* tim tim))))
                           state        (cond-> state
                                          (balls key-time) (assoc :arc
                                                                  (hit (assoc arc
                                                                              :start-time key-time
                                                                              :x          x
                                                                              :y          y
                                                                              :vy         (- vy (* gravity tim)))
                                                                       (balls key-time))))]
                       (-> state
                           (assoc :animation-ball
                                  [x y])
                           (update :animation-time
                                   (fn [tim]
                                     (mod (+ tim 1) (* subframes 100))))))))))

(defn set-state-from-props [props]
  (update-state! assoc
                 :balls
                 (into {}
                       (for [[k [x y :as ball]] (:bounce/balls props)]
                         [k [x (- 400 y)]]))))

(defcomponent Root
  (state {:animation-time 0})
  (query [[:ball/current] [:bounce/current-time] [:bounce/balls]])
  (component-did-mount [atts]
                       (set-state-from-props atts)                       
                       (update-state! assoc
                                      :interval
                                      (js/setInterval (partial animate-ball qr/*this*)
                                                      (/ 1000 60) #_20)))
  (component-will-unmount [state]
                          (js/clearInterval (:interval state)))
  (component-will-receive-props [{:keys [:bounce/balls] :as atts}]
                                #_(?? balls)
                                (set-state-from-props atts))
  (render [{:keys [:ball/current :bounce/current-time :bounce/balls] :as atts} {:keys [ball-down animation-ball animation-time] :as state}]
          (let [[x y]    current
                mouse-fn (fn [key]
                           (fn [e]
                             (transact! [key
                                         {:value (let [t  (.-currentTarget e)
                                                       br (.getBoundingClientRect t)
                                                       xx (- (.-clientX e) (.-left br))
                                                       yy (- (.-clientY e) (.-top br))]
                                                   [(/ (* 600 xx) (.-offsetWidth t)) (/ (* 400 yy) (.-offsetHeight t))])}])))
                ball     (fn [tim color x y transparent]
                           [:div {:position         :absolute
                                  :width            "4%"
                                  :height           "6%"
                                  :border-radius    "50%"
                                  :cursor           :pointer
                                  :margin-left      "-2%"
                                  :margin-top       "-2%"
                                  :left             (str (/ x 6) "%")
                                  :top              (str (/ y 4) "%")
                                  :background-color color
                                  :background-image (when (= color :image)
                                                      "url(ball.png)")
                                  :font-size        "80%"
                                  :background-size  "cover"
                                  :color            :grey
                                  :text-align       :center
                                  :display          :flex
                                  :justify-content  :center
                                  :align-items      :center
                                  :on-click         (fn []
                                                      (when (not= current-time tim)
                                                        (transact! [:bounce/current-time! {:value tim}])))
                                  :opacity          (if transparent
                                                      0.4
                                                      1)}
                            tim])
                derp balls]
            #_(?? derp)
            [:card {:max-width 600
                    :margin    :auto
                    :rounded false}
             [:card-header {:title "Physics Animation Experiment"}]
             [:div {:background-image "url(background.jpg)"
                    :user-select      :none
                    :overflow         :hidden
                    :padding-bottom   "66%"
                    :position         :relative
                    :on-mouse-down    (mouse-fn :current-ball/drag-start!)
                    :on-mouse-move    (mouse-fn :current-ball/drag-end!)
                    :on-mouse-leave   (mouse-fn :current-ball/move-cancel!)
                    :on-mouse-up      (mouse-fn :current-ball/move!)}
              (for [[k v] balls]
                (ball k "#CCC" (first v) (second v) (= k current-time)))
              (when current
                (ball current-time "#BCF" x y false))
              (ball "" #_(int (/ animation-time subframes)) :image (first animation-ball) (- 400 (second animation-ball)) false)]
             [:card-actions [:flat-button {:primary  true
                                           :disabled (boolean current)
                                           :label    "New Keyframe"
                                           :on-click (fn []
                                                       (transact! [:bounce/new-keyframe! {:time current-time}]))}]]
             [:card-text
              {:text-align :center}
              current-time
              [:slider {:min       0
                        :max       100
                        :step      1
                        :value     current-time
                        :on-change (fn [_ value]
                                     (transact! [:bounce/current-time! {:value value}]))}]]

             #_[:a {:href "https://www.freepik.com/free-photo/3d-wooden-table-against-grunge-wall-with-light-shining-from-the-left_1594722.htm"} "Background Image Designed by Freepik"]])))

(defn remote-handler [query callback]
  (go (let [{:keys [status body] :as result} (<! (post "endpoint" {:edn-params query}))]
        (if (not= status 200)
          (print "server error: " body)
          (callback (read-string body))))))

(ql/mount {:component      Root
           :dom-element    (getElement "app")
           :state          app-state
           :remote-handler remote-handler
           :parsers        {:read   read
                            :mutate mutate}})
