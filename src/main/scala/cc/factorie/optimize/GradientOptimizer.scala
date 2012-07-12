package cc.factorie.optimize

import _root_.scala.math
import _root_.scala.Predef._
import cc.factorie._
import cc.factorie.la._

/** Repeatedly call "step" until "isConverged" is true. */
trait GradientOptimizer {
  //def step(gradient:Tensor, value:Double, margin:Double): Unit
  def step(weights:Tensor, gradient:Tensor, value:Double, margin:Double): Unit
  def isConverged: Boolean
  def reset(): Unit
}

/** Keeps an average of all weight settings throughout steps. 
    To get "average perceptron" use "new WeightsAveraging(new StepwiseGradientAscent)" */
class WeightsAveraging(val inner:GradientOptimizer) extends GradientOptimizer {
  var weightsSum: Tensor = null
  var normalizer = 0.0
  def reset(): Unit = {
    weightsSum = null
    normalizer = 0.0
    inner.reset()
  }
  def step(weights:Tensor, gradient:Tensor, value:Double, margin:Double): Unit = {
    if (weightsSum eq null) weightsSum = weights.copy
    else weightsSum += weights // Yipes, this is not sparse, not efficient
    normalizer += 1.0
    inner.step(weights, gradient, value, margin)
  }
  def averageWeights: Tensor = weightsSum / normalizer 
  def isConverged: Boolean = inner.isConverged
}

/** Change the weights in the direction of the gradient by a factor of "rate" for each step. */
class StepwiseGradientAscent(var rate: Double = 1.0) extends GradientOptimizer {
  def step(weights:Tensor, gradient:Tensor, value:Double, margin:Double): Unit = {
    weights.+=(gradient, rate)
    rate = nextRate(rate)
  }
  def nextRate(oldRate:Double): Double = oldRate // TODO What should go here?
  def isConverged = false // TODO What to put here?
  def reset(): Unit = {}
}

/** Change the weights in the direction of the gradient by using back-tracking line search to make sure we step up hill. */
class LineSearchGradientAscent(var stepSize: Double = 1.0) extends GradientOptimizer with FastLogging {
  private var _isConverged = false
  def isConverged = _isConverged
  var gradientTolerance = 0.001
  var valueTolerance = 0.0001
  var gradientNormMax = 100.0
  var eps = 1.0e-10
  var oldValue = Double.NaN
  var lineOptimizer: BackTrackLineOptimizer2 = null
  def reset(): Unit = {
    _isConverged = false
    oldValue = Double.NaN
  }
  def step(weights:Tensor, gradient:Tensor, value:Double, margin:Double): Unit = {
    if (_isConverged) return
    // Check for convergence by value
    if (2.0 * math.abs(value - oldValue) < valueTolerance * (math.abs(value) + math.abs(oldValue) + eps)) {
      logger.info("GradientAscent converged: old value="+oldValue+" new value="+value+" tolerance="+valueTolerance)
      _isConverged = true
      return
    }
    // Check for convergence by gradient
    val gradientTwoNorm = gradient.twoNorm
    if (gradientTwoNorm < gradientTolerance) {
      logger.info("GradientAscent converged: gradient twoNorm="+gradient.twoNorm+" tolerance="+gradientTolerance)
      _isConverged = true
      return
    }

    if (lineOptimizer eq null) {
      // Before giving the BackTrackLineOptimizer a line direction to search, ensure it isn't too steep
     // if (gradientTwoNorm > gradientNormMax) gradient.*=(gradientNormMax / gradientTwoNorm)
      lineOptimizer = new BackTrackLineOptimizer2(gradient, gradient.copy, stepSize)
      oldValue = value
    }
    lineOptimizer.step(weights, gradient, value, margin)
    if (!lineOptimizer.isConverged) return
    lineOptimizer = null // So we create a new one next time
    lineOptimizer = new BackTrackLineOptimizer2(gradient, gradient.copy, stepSize)
    lineOptimizer.step(weights, gradient, value, margin)
    oldValue = value
  }
}

class BackTrackLineOptimizer2(val gradient:Tensor, val line:Tensor, val initialStepSize:Double = 1.0) extends GradientOptimizer with FastLogging {
  private var _isConverged = false
  def isConverged = _isConverged
  def stepSize = alam
  
  var gradientNormMax = 100.0
  var relTolx = 1e-7
  var absTolx = 1e-4
  var ALF = 1e-4
  val EPS = 3.0e-12
  val stpmax = 100.0
  var origWeights: Tensor = null //weights.copy

  var oldValue = Double.NaN
  var origValue = Double.NaN
  var slope = Double.NaN
  var alamin = Double.NaN
  var alam = initialStepSize
  var oldAlam = 0.0;
  var tmplam = 0.0;
  var alam2 = 0.0

  def reset(): Unit = {
    _isConverged = false
    origWeights = null
    oldValue = Double.NaN
    origValue = Double.NaN
    slope = Double.NaN
    alamin = Double.NaN
    alam = initialStepSize
    oldAlam = 0.0;
    tmplam = 0.0;
    alam2 = 0.0
  }
    def step(weights:Tensor, gradient:Tensor, value:Double, margin:Double): Unit = {
    logger.warn("BackTrackLineOptimizer step value="+value)
    // If first time in, do various initializations
    if (slope.isNaN) {
      origWeights = weights.copy
      // Set the slope

      val sum = line.twoNorm
      if (sum > initialStepSize) line *= (initialStepSize / sum) // If gradient is too steep, bring it down to gradientNormMax
      slope = gradient dot line     //todo, it's redundant, and expensive to do both gradient.twoNorm and gradient dot line when they're the same
      logger.warn("BackTrackLineOptimizer slope="+slope)
      if (slope <= 0.0) throw new Error("Slope=" + slope + " is negative or zero.")

      // Set alamin
      // Find maximum lambda; converge when (delta x) / x < REL_TOLX for all coordinates.
      // Largest step size that triggers this threshold is saved in alamin
      var test = 0.0;
      var temp = 0.0
      if (!weights.dimensionsMatch(line)) throw new Error("line and weights do not have same dimensionality.")
      // Do the check above because toArray will yield non-matching results if called on a WeightsTensor that has missing keys.
      val lineA = line.toArray
      val weightsA = weights.toArray
      var i = 0; val len = lineA.length
      while (i < len) {
        temp = math.abs(lineA(i)) / math.max(math.abs(weightsA(i)), 1.0)
        if (temp > test) test = temp
        i += 1
      }
      alamin = relTolx / test

      // Set oldValue and origValue
      oldValue = value
      origValue = value
      logger.warn("BackTrackLineOptimizer line factor="+(alam-oldAlam))
      if(!_isConverged) weights.+=(line, alam - oldAlam)

    }else{

      // Check for convergence by sufficient function increase (Wolf condition)
      if (value >= origValue + ALF * alam * slope) {
        if (value < origValue) throw new Error("value did not increase: original=" + origValue + " new=" + value)
        _isConverged = true
      } else if (value.isInfinity || oldValue.isInfinity) {
        // value is infinite; we have jumped into unstable territory.  Scale down jump
        tmplam =.2 * alam
        if (alam < alamin) {
          logger.warn("BackTrackLineOptimizer EXITING BACKTRACK: Jump too small. Exiting and using xold.");
          _isConverged = true // Exiting backtrack: jump to small; using previous parameters
        }
      }else if (alam == oldAlam){
          _isConverged = true
      } else {
        // backtrack
        if (alam == 1.0) tmplam = { -slope / (2.0 * (value - origValue - slope))} // first time through
        else {
          val rhs1 = value - origValue - alam * slope
          val rhs2 = oldValue - origValue - alam2 * slope
          assert((alam - alam2) != 0, "FAILURE: dividing by alam-alam2.  alam=" + alam)
          val a = (rhs1 / (alam * alam) - rhs2 / (alam2 * alam2)) / (alam - alam2)
          val b = (-alam2 * rhs1 / (alam * alam) + alam * rhs2 / (alam2 * alam2)) / (alam - alam2);
          if (a == 0.0) tmplam = -slope / (2.0 * b)
          else {
            val disc = b * b - 3.0 * a * slope
            if (disc < 0.0) tmplam =.5 * alam
            else if (b <= 0.0) tmplam = (-b + math.sqrt(disc)) / (3.0 * a)
            else tmplam = -slope / (b + math.sqrt(disc))
            if (tmplam > .5 * alam) tmplam =.5 * alam
          }
        }
      }
      alam2 = alam
      oldValue = value
      oldAlam = alam
      alam = math.max(tmplam, 0.1 * alam)
      if(alam == oldAlam) _isConverged = true

      if(!_isConverged){
        logger.warn("BackTrackLineOptimizer line factor="+(alam-oldAlam))
        weights.+=(line, alam - oldAlam)
      }

    }

    // Check for convergence
    if (alam < alamin || !origWeights.different(weights, absTolx)) {
      weights := origWeights
      logger.warn("EXITING BACKTRACK: Jump too small (alamin=" + alamin + "). Exiting and using xold.");
      _isConverged = true // Convergence on change in params
    }
  }

}


class ConjugateGradient2(val initialStepSize: Double = 1.0) extends GradientOptimizer with FastLogging {
  private var _isConverged = false
  def isConverged = _isConverged
   
  var tolerance = 0.0001
  var gradientTolerance = 0.001
  var maxIterations = 1000
  val eps = 1.0e-10 // a small number to rectify the special case of converging to exactly zero function value

  // The state of a conjugate gradient search
  //var fp = 0.0
  var oldValue = 0.0
  var gg = 0.0
  var gam = 0.0
  var dgg = 0.0
  var stepSize = 0.0
  var xi: Tensor = null
  var g: Tensor = null
  var h: Tensor = null
  var iterations = 0
  var lineOptimizer: BackTrackLineOptimizer2 = null

  def reset(): Unit = {
    xi = null
    _isConverged = false
  }

  def step(weights:Tensor, gradient:Tensor, value:Double, margin:Double): Unit = {
    if (_isConverged) return
    
    // If this is our first time in, then initialize
    if (xi eq null) {
      xi = gradient.copy
      g = xi.copy
      h = xi.copy
      stepSize = initialStepSize
    }
    
    // Take a step in the current search direction, xi
    if (lineOptimizer eq null) lineOptimizer = new BackTrackLineOptimizer2(gradient, xi.copy, stepSize)
    lineOptimizer.step(weights, xi, value, margin)
    // If the lineOptimizer has not yet converged, then don't yet do any of the ConjugateGradient-specific things below
    if (!lineOptimizer.isConverged) return
    lineOptimizer = null // So we create a new one next time around
    xi = gradient.copy
    // This termination provided by "Numeric Recipes in C".
    if (2.0 * math.abs(value - oldValue) <= tolerance * (math.abs(value) + math.abs(oldValue) + eps)) {
      logger.info("ConjugateGradient converged: old value="+oldValue+" new value="+value+" tolerance="+tolerance)
      _isConverged = true;
      return
    }
    // This termination provided by McCallum
    if (xi.twoNorm < gradientTolerance) {
      logger.info("ConjugateGradient converged: maximum gradient component: "+xi.twoNorm+" less than "+tolerance)
      _isConverged = true;
      return
    }

    oldValue = value

    // compute gamma, new g and new h
    {
      dgg = 0.0
      gg = 0.0
      val xia = xi.asArray
      val ga = g.asArray
      var i = 0
      while (i < ga.length) {
        gg += ga(i) * ga(i) // previous gradient
        dgg += xia(i) * (xia(i) - ga(i)) // current gradient
        i += 1
      }
      gam = dgg / gg
      g := xi
      h *= gam; h += g
      assert(!h.containsNaN)
    }
    
    /* gdruck: If using the BackTrackLineSearch, then the search stops whenever
       a step is found that increases the value significantly (according
       to a threshold from Numerical Recipes).  ConjugateGradient
       assumes that line maximization finds something close
       to the maximum in that direction.  In tests, sometimes the
       direction suggested by CG points downhill.  Consequently, here I am
       setting the search direction to the gradient if the slope is
       negative or 0. */
    // TODO Implement GradientBracketLineMaximizer (used in Numerical Recipes) which should avoid this problem!
    if (xi.dot(h) > 0) xi := h  else h := xi

    iterations += 1

    lineOptimizer = new BackTrackLineOptimizer2(gradient, xi.copy, stepSize)
    lineOptimizer.step(weights, xi, value, margin)
  }
}