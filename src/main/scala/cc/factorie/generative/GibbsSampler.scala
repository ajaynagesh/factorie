/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */



package cc.factorie.generative
import cc.factorie._
import scala.reflect.Manifest 
import scala.collection.mutable.{HashMap, HashSet, PriorityQueue, ArrayBuffer}

// How to think about Proposals and MCMC:
// Variables know their own range of values.  This needs to be coded on a per-variable basis
// Scored preferences about different values are known only by using the model.
// Sometimes we want to sample more than one variable together.  One variable cannot know how to do this on its own.
// Sometimes we want to sample conditioned on other fixed values.  One variable cannot know about this either.  It must be something like a Template
// Sometimes we want sampling to chain: sample v1, then v2 conditioned on the value of v1, etc.
// Making proposals is sometimes keyed by a single variable, a list of variables, or nothing (proposer itself maintains context of what to change next)
// Perhaps proposers should be in a list of Template-like objects; given a variable, first Template in the list to claim it gets to make the change.
// To facilitate ease of use, variable classes could provide something like:
//   class Label[T] { def defaultSampler = LabelSampler; def sample(model:Model) = defaultSampler.sample(this,model) }
//   object LabelSampler extends Sampler1[Label]
    

/** Simple GibbsSampler.
    @author Andrew McCallum */
class GibbsSampler(val model:Model = cc.factorie.generative.defaultGenerativeModel) extends Sampler[Iterable[Variable]] {
  var temperature = 1.0
  val handlers = new ArrayBuffer[GibbsSamplerHandler]
  def defaultHandlers = List(GeneratedVarGibbsSamplerHandler) //, MixtureChoiceGibbsSamplerHandler, IterableSettingsGibbsSamplerHandler
  handlers ++= defaultHandlers
  val cacheClosures = true
  def closures = new HashMap[Variable, GibbsSamplerClosure]
  // TODO Consider Either[] type checking instead of generative Sampler[Variable]
  def process1(v:Iterable[Variable]): DiffList = {
    val d = newDiffList
    // If we have a cached closure, just use it and return
    if (cacheClosures && v.size == 1 && closures.contains(v.head)) { closures(v.head).sample(d); return d }
    // Get factors, in sorted order of the their classname
    val factors = model.factors(v).sortWith((f1:Factor,f2:Factor) => f1.getClass.getName < f2.getClass.getName).toList
    var done = false
    val handlerIterator = handlers.iterator
    while (!done && handlerIterator.hasNext) {
      val closure = handlerIterator.next.sampler(v, factors, this)
      if (closure ne null) {
        done = true
        closure.sample(d)
        if (cacheClosures && v.size == 1) closures(v.head) = closure
      }
    }
    if (!done) throw new Error("GibbsSampler: No sampling method found for "+factors)
    d
  }
  /** Special-case for one variable */
  def process(v:Variable): DiffList = process(List(v))
}

trait GibbsSamplerHandler {
  def sampler(v:Iterable[Variable], factors:List[Factor], sampler:GibbsSampler): GibbsSamplerClosure
}
trait GibbsSamplerClosure {
  def sample(implicit d:DiffList = null): Unit
}


object GeneratedVarGibbsSamplerHandler extends GibbsSamplerHandler {
  class Closure(val variable:MutableGeneratedVar, val factor:GenerativeFactor) extends GibbsSamplerClosure {
    def sample(implicit d:DiffList = null): Unit = variable.set(variable.sampledValue)
  }
  def sampler(vs:Iterable[Variable], factors:List[Factor], sampler:GibbsSampler): GibbsSamplerClosure = {
    factors match {
      case List(factor:GenerativeFactor) => {
        vs.head match {
          case v:MutableGeneratedVar => new Closure(v, factor)
        }
      }
      case _ => null
    }
  }
}





/*
object MixtureChoiceGibbsSamplerHandler extends GibbsSamplerHandler {
  def sample(v:Iterable[Variable], factors:List[Factor], sampler:GibbsSampler)(implicit d:DiffList): Boolean = {
    if (v.size != 1) return false
    v.head match {
      case mc:MixtureChoiceVar => {
        // TODO We really should have a more careful check like this
        //if (!(factors.forall(_ match { case f:GeneratedVarTemplate#Factor => f.n1 == v || f.n2 == v; case _ => false }))) return false
        val outcomes: Seq[MixtureGeneratedVar] = mc.outcomes
        val domainSize = mc.domain.size
        val distribution = new Array[Double](domainSize)
        var sum = 0.0
        for (i <- 0 until domainSize) {
          distribution(i) = mc.prChoosing(i) * outcomes.foldLeft(1.0)((prod:Double, outcome:MixtureGeneratedVar) => prod * outcome.prChoosing(i))
          sum += distribution(i)
        }
        mc.set(mc.domain(maths.nextDiscrete(distribution, sum)(cc.factorie.random)))(d)
        true
      }
    }
  }
}

object IterableSettingsGibbsSamplerHandler extends GibbsSamplerHandler {
  def sample(v:Iterable[Variable], factors:List[Factor], sampler:GibbsSampler)(implicit d:DiffList): Boolean = {
    if (v.size != 1) return false
    v.head match {
      case v2: Variable with IterableSettings => {
        // Iterate over all settings of the variable 'v', score each change, and sample from those scores
        val proposals = v2.settings.map(d => {val m = d.scoreAndUndo(sampler.model); new Proposal(d, m, Double.NaN, m/sampler.temperature)}).toList
        val proposal = proposals.sampleExpProportionally((p:Proposal) => p.acceptanceScore)
        proposal.diff.redo
        if (d ne null) d ++= proposal.diff
        true
      }
      case _ =>  false
    }
  }
}
*/
