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

(defonce app-state (atom {:ball/by-time {0 [302 352.52525252525254],
                                         2 [244 319.1919191919191],
                                         35 [108 111.11111111111114],
                                         4 [184 350.5050505050506],
                                         13 [127 336.36363636363643],
                                         45 [159 64.64646464646466],
                                         77 [486 243.43434343434348],
                                         16 [53 355.55555555555526],
                                         81 [588 260.6060606060606],
                                         51 [237 145.45454545454544],
                                         86 [551 359.5959595959596],
                                         89 [490 298.989898989899],
                                         26 [73 41.414141414141454],
                                         91 [445 356.56565656565664],
                                         94 [375 307.07070707070704]}
                          :bounce/current-time 0}))

(defn sqr [x]
  (* x x))

(defn sqrt [x]
  (js/Math.sqrt x))

(defn hit [{:keys [x y vx vy] :as arc} [xtarget ytarget] gravity friction]
  (let [x             (- xtarget x)
        y             (- ytarget y)        
        theta-fric    (js/Math.atan2 vy vx)
        theta-targ    (js/Math.atan2 y x)
        friction      (/ (* friction (js/Math.abs (js/Math.atan2 (js/Math.sin (- theta-fric theta-targ)) (js/Math.cos (- theta-fric theta-targ))))) 3.14159)
        [vx vy]       [(* (- 1 friction) vx)
                       (* (- 1 friction) vy)]
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
                            (pos? y)          (let [theta (js/Math.atan2 (* 2 y) x)
                                                    v  (sqrt (/ (* y 2 gravity) (sqr (js/Math.sin theta))))]
                                                [(* v (js/Math.cos theta)) (* v (js/Math.sin theta))])
                            :else             (let [t  (/ (sqrt (* 2 (- y))) (sqrt gravity))
                                                    vx (/ x t)]
                                                [vx 0]))]
    (assoc arc
           :vx vx
           :vy vy
           :stop-time (min 150 (/ x vx)))))

(defn find-next-keyframe [balls cur-keyframe]
  (let [bigger (sort (filter (partial < cur-keyframe) (keys balls)))]
    (if (seq bigger)
      (first bigger)
      (first (sort (keys balls))))))

(defn gravity [gravity-exp]
  (dec (js/Math.pow 10 gravity-exp)))

(defn animate-ball [this]
  (binding [qr/*this* this]
    (update-state! (fn [{:keys [arc animation-time balls gravity-exp friction] :as state}]
                     (let [gravity                                    (gravity gravity-exp)
                           {:keys [arc] :as state}                    (cond-> state
                                                                        (not arc) (assoc :arc
                                                                                         {:cur-keyframe -1
                                                                                          :x          100
                                                                                          :y          0
                                                                                          :vx         0
                                                                                          :vy         0}))
                           {:keys [stop-time x y vx vy cur-keyframe]} arc
                           x                                          (+ x (* vx animation-time))
                           y                                          (+ y (* vy animation-time) (- (* 0.5 gravity (sqr animation-time))))
                           state                                      (cond-> state
                                                                        (> animation-time stop-time) (assoc :animation-time 0
                                                                                                            :arc
                                                                                                            (let [next-keyframe (find-next-keyframe balls cur-keyframe)]
                                                                                                              (hit (assoc arc
                                                                                                                          :x          x
                                                                                                                          :y          y
                                                                                                                          :cur-keyframe next-keyframe
                                                                                                                          :vy         (- vy (* gravity animation-time)))
                                                                                                                   (balls next-keyframe)
                                                                                                                   gravity
                                                                                                                   friction))))]
                       (-> state
                           (assoc :animation-ball
                                  [x y])
                           (update :animation-time inc)
                           (assoc :animation-id (js/requestAnimationFrame (partial animate-ball this)))))))))

(defn set-state-from-props [props]
  (update-state! assoc
                 :balls
                 (into {}
                       (for [[k [x y :as ball]] (:bounce/balls props)]
                         [k [x (- 400 y)]]))))

(defcomponent Root
  (state {:animation-time 0 :gravity-exp 0.2 :friction 0.29})
  (query [[:ball/current] [:bounce/current-time] [:bounce/balls]])
  (component-did-mount [atts]
                       (set-state-from-props atts)                       
                       (update-state! assoc
                                      :animation-id
                                      (js/requestAnimationFrame (partial animate-ball qr/*this*))))
  (component-will-unmount [state]
                          (js/cancelAnimationFrame (:animation-id state)))
  (component-will-receive-props [{:keys [:bounce/balls] :as atts}]
                                (set-state-from-props atts))
  (render [{:keys [:ball/current :bounce/current-time :bounce/balls] :as atts} {:keys [ball-down animation-ball animation-time gravity-exp friction] :as state}]
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
                                  :opacity          (cond transparent 0.2
                                                          (= color :image) 1
                                                          :else            0.5)}
                            tim])
                derp balls]
            [:card {:max-width 600
                    :margin    :auto
                    :rounded false}
             [:card-header {:title "Physics Animation Experiment"}]
             [:card-text "Be sure to read my " [:a {:href "https://medium.com/p/a986ea46f587"}
                                             "blog post on this little experiment!"]]
             [:div {:background-image "url(background.jpg)"
                    :user-select      :none
                    :overflow         :hidden
                    :padding-bottom   "66%"
                    :position         :relative
                    :color :silver
                    :font-size        "70%"
                    :on-mouse-down    (mouse-fn :current-ball/drag-start!)
                    :on-mouse-move    (mouse-fn :current-ball/drag-end!)
                    :on-mouse-leave   (mouse-fn :current-ball/move-cancel!)
                    :on-mouse-up      (mouse-fn :current-ball/move!)}
              [:div {:position :absolute
                     :right "4%"
                     :top "6%"} "Click on handles to select or move them"]
              (for [[k v] balls]
                (ball k "#CCC" (first v) (second v) (= k current-time)))
              (when current
                (ball current-time "#BCF" x y false))
              (ball "" #_(int (/ animation-time subframes)) :image (first animation-ball) (- 400 (second animation-ball)) false)]
             [:card-actions
              [:flat-button {:primary  true
                             :disabled (boolean current)
                             :label    "New Handle"
                             :on-click (fn []
                                         (transact! [:bounce/new-keyframe! {:time current-time}]))}]
              [:flat-button {:primary  true
                                           :disabled (not current)
                                           :label    "Delete Handle"
                                           :on-click (fn []
                                                       (transact! [:bounce/delete-keyframe! {:time current-time}]))}]]
             [:card-text
              {:text-align :center}
              "Handle: " current-time
              [:slider {:min       0
                        :max       100
                        :step      1
                        :value     current-time
                        :on-change (fn [_ value]
                                     (transact! [:bounce/current-time! {:value value}]))}]
              "Gravity: " (/ (int (* (gravity gravity-exp) 100)) 100)
              [:slider {:min       0.01
                        :max       1.2
                        :step      0.01
                        :value     gravity-exp
                        :on-change (fn [_ value]
                                     (update-state! assoc :gravity-exp value))}]
              "Friction: " friction
              [:slider {:min       0.01
                        :max       0.99
                        :step     0.01
                        :value    friction
                        :on-change (fn [_ value]
                                     (update-state! assoc :friction value))}]]
             [:card-text "Built by Conrad Barski using ForwardBlockchain's " [:a {:href "http://qlkit.org"} "Qlkit Web Development Framework"]]
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

