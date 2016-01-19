package org.openworm.trackercommons

import java.io.File

import scala.util._
import scala.util.control.NonFatal

case class FileSet(names: Vector[String], index: Int, custom: json.ObjJ)
extends json.Jsonable {
  if (index < 0 || index >= math.max(1,names.length)) throw new NoSuchElementException("FileSet index out of range")

  val files = collection.mutable.LongMap.empty[File]
  def lookup(i: Int): Option[File] = 
    if (i+index < 0 || i + index >= names.length) None
    else if (i == 0) files get 0
    else (files get i) match {
      case sf: Some[File] => sf
      case None => (files get 0) match {
        case None => None
        case Some(root) =>
          Try{ FileSet.refile(root, names(index), names(i+index)).right.get.getCanonicalFile } match {
            case Success(fi) =>
              files += ((i, fi))
              Some(fi)
            case _ => None
          }
      }
    }
  def setRootFile(f: File) {
    val cf = f.getCanonicalFile
    if (!files.get(0).exists(_ == cf)) {
      if (FileSet.indexInPath(cf.getPath, names(index)) < 0) throw new Exception(s"Cannot find pattern ${names(index)} in root file $f")
      files.clear
      files += ((0, cf))
    }
  }

  def me = if (names.length == 0) "" else names(index)
  def you(i: Int) = names(i + index)
  def indices = -index until names.length - index

  def join(delta: Int, that: FileSet): Either[String, FileSet] = {
    val cf = lookup(0) match { case None => return Left("Cannot join file sets without a known root file"); case Some(x) => x }
    val cfp = cf.getPath
    val tcf = that.lookup(0) match { case None => return Left("Cannot join file sets without a known root file"); case Some(x) => x }
    val tcfp = tcf.getPath
    val tcfb = lookup(delta) match { case None => return Left("Cannot join file sets without a known root file"); case Some(x) => x }
    if (tcf != tcfb) return Left(s"File sets do not correspond, expect these to be identical:\n  $tcf\n  $tcfb")
    val ime = FileSet.indexInPath(cfp, me)
    val iyou = FileSet.indexInPath(tcfp, that.me)
    if (ime < 0 || iyou < 0) return Left("Internal error: self-pattern does not agree with root file name?")
    val deeproot = cfp.take(math.min(ime, iyou))
    val deeptail = cfp.takeRight(math.min(cfp.length - (ime + me.length), tcfp.length - (iyou + that.me.length)))
    val preme = cfp.substring(math.min(ime, iyou), ime)
    val preyou = tcfp.substring(math.min(ime, iyou), iyou)
    val postme = cfp.substring(ime + me.length, cfp.length - deeptail.length)
    val postyou = tcfp.substring(iyou + that.me.length, tcfp.length - deeptail.length)
    val i0 = math.min(indices.head, that.indices.head + delta)
    val i1 = math.max(indices.last, that.indices.last + delta)
    val v = Vector.tabulate(1+i1-i0){ i =>
      val j = i + i0
      val k = j - delta
      if (indices contains j) {
        if (that.indices contains k) {
          val sme = if (preme.isEmpty && postme.isEmpty) you(j) else preme + you(j) + postme
          val syou = if (preyou.isEmpty && postyou.isEmpty) that.you(k) else preyou + that.you(k) + postyou
          if (sme == syou) sme
          else {
            val fj = lookup(j) match { case None => return Left("Could not find canonical file for ${you(j)} in $cfp"); case Some(x) => x }
            if (fj == that.lookup(k)) fj.getPath.drop(deeproot.length).dropRight(deeptail.length)
            else return Left(s"Mismatch in order of data files listed in:\n  $cfp\n  $tcfp\nFull paths:\n  $fj\n  ${that.lookup(k)}")
          }
        }
        else {
          if (preme.isEmpty && postme.isEmpty) you(j) else preme + you(j) + postme
        }
      }
      else if (that.indices contains k) {
        if (preyou.isEmpty && postyou.isEmpty) that.you(k) else preyou + that.you(k) + postyou
      }
      else return Left(s"Internal error: cannot index into $j / $k during FileSet.join")
    }
    val fs = new FileSet(v, index + (indices.head - i0), json.ObjJ.empty)
    files.foreach{ case (key, value) => fs.files += ((key + (indices.head - i0), value)) }
    that.files.foreach{ case (key, value) => fs.files += ((key + (that.indices.head + delta - i0), value)) }
    Right(fs)
  }

  def toObjJ = {
    var m = Map("this" -> ((json.StrJ(me): json.JSON) :: Nil))
    if (index > 0) m = m + ("prev" -> (json.ArrJ(names.take(index).map(x => json.StrJ(x): json.JSON).toArray.reverse) :: Nil))
    if (names.length - 1 > index) m = m + ("next" -> (json.ArrJ(names.drop(index+1).map(x => json.StrJ(x): json.JSON).toArray) :: Nil))
    json.ObjJ(m ++ custom.keyvals)
  }

  def size = names.size
}
object FileSet extends json.Jsonic[FileSet] {
  private def BAD(msg: String): Either[String, Nothing] = Left("Invalid files specification: " + msg)

  // Find the index of a name inside a path.  Assume it might be Windows with wonky capitalization and slash-vs-backslash
  private[trackercommons]
  def indexInPath(path: String, me: String): Int = {
    val r5 = path.takeRight(5)
    val isJsonWcon = (r5 equalsIgnoreCase ".json") || (r5 equalsIgnoreCase ".wcon")
    val index = path.lastIndexOf(me, if (isJsonWcon) path.length-5-me.length else path.length-me.length)
    if (index >= 0) index
    else path.
      toLowerCase.
      replace("\\","/").
      lastIndexOf(
        me.toLowerCase.replace("\\","/"),
        path.length - me.length - (if (isJsonWcon) 5 else 0)
      )
  }

  private[trackercommons]
  def refile(base: File, me: String, you: String): Either[String, File] = {
    val path = base.getPath
    val i = indexInPath(path, me)
    if (i < 0) Left(s"Cannot find $me in $path so cannot find other files in set")
    else Right(new File(path.take(i) ++ you ++ path.drop(i + me.length)))
  }

  def empty = new FileSet(Vector.empty, 0, json.ObjJ.empty)

  def from(ob: json.ObjJ): Either[String, FileSet] = {
    val me = ob.keyvals.get("this") match {
      case None => return BAD("extension for this file not specifed")
      case Some(json.StrJ(x) :: Nil) => x
      case _ => return BAD("file extension must be exactly one string")
    }
    val List(pv, nx) = List("prev", "next").map(ident => ob.keyvals.get(ident) match {
      case None => Nil
      case Some(js) => js.flatMap{
        case json.NullJ => Nil
        case json.StrJ(x) => x :: Nil
        case json.ArrJ(xs) => xs.map{
          case json.StrJ(x) => x
          case _ => return BAD("non-text file name in " + ident)
        }
        case _ => return BAD("non-text file name in " + ident)
      }
    })
    val pvl = pv.length
    Right(new FileSet((pv.reverse.toVector :+ me) ++ nx, pvl, Metadata.getCustom(ob)))
  }
}
