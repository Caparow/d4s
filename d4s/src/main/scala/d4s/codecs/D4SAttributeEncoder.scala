package d4s.codecs

import java.util.UUID

import magnolia.{Magnolia, ReadOnlyCaseClass, SealedTrait}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

import scala.language.experimental.macros

import scala.jdk.CollectionConverters._

trait D4SAttributeEncoder[T] {
  def encode(item: T): AttributeValue

  def contramap[T1](f: T1 => T): D4SAttributeEncoder[T1]                                       = item => encode(f(item))
  def postprocessAttributeEncoder(f: AttributeValue => AttributeValue): D4SAttributeEncoder[T] = item => f(encode(item))
}

object D4SAttributeEncoder extends D4SAttributeEncoderScala213 {
  @inline def apply[T: D4SAttributeEncoder]: D4SAttributeEncoder[T] = implicitly

  def derived[T]: D4SAttributeEncoder[T] = macro Magnolia.gen[T]

  def encode[T: D4SAttributeEncoder](item: T): AttributeValue                                 = D4SAttributeEncoder[T].encode(item)
  def encodeField[T: D4SAttributeEncoder](name: String, item: T): Map[String, AttributeValue] = Map(name -> D4SAttributeEncoder[T].encode(item))

  /** Magnolia instances */
  private[D4SAttributeEncoder] type Typeclass[T] = D4SAttributeEncoder[T]

  def combine[T](ctx: ReadOnlyCaseClass[D4SAttributeEncoder, T]): D4SAttributeEncoder[T] = {
    if (ctx.isObject) {
      new CaseObjectEncoder[T](ctx.typeName.short)
    } else {
      item =>
        val map = ctx.parameters.map {
          p =>
            p.label -> p.typeclass.encode(p.dereference(item))
        }.toMap
        AttributeValue.builder().m(map.asJava).build()
    }
  }

  def dispatch[T](ctx: SealedTrait[D4SAttributeEncoder, T]): D4SAttributeEncoder[T] = {
    item =>
      ctx.dispatch(item) {
        subtype =>
          if (subtype.typeclass.isInstanceOf[CaseObjectEncoder[_]]) {
            subtype.typeclass.encode(subtype.cast(item))
          } else {
            AttributeValue.builder().m(Map(subtype.typeName.short -> subtype.typeclass.encode(subtype.cast(item))).asJava).build()
          }
      }
  }

  implicit val attributeEncoder: D4SAttributeEncoder[AttributeValue] = a => a
  implicit val stringEncoder: D4SAttributeEncoder[String]            = AttributeValue.builder().s(_).build()
  implicit val boolEncoder: D4SAttributeEncoder[Boolean]             = AttributeValue.builder().bool(_).build()
  implicit val byteEncoder: D4SAttributeEncoder[Byte]                = numericAttributeEncoder[Byte]
  implicit val shortEncoder: D4SAttributeEncoder[Short]              = numericAttributeEncoder[Short]
  implicit val intEncoder: D4SAttributeEncoder[Int]                  = numericAttributeEncoder[Int]
  implicit val longEncoder: D4SAttributeEncoder[Long]                = numericAttributeEncoder[Long]
  implicit val floatEncoder: D4SAttributeEncoder[Float]              = numericAttributeEncoder[Float]
  implicit val doubleEncoder: D4SAttributeEncoder[Double]            = numericAttributeEncoder[Double]
  implicit val unitEncoder: D4SAttributeEncoder[Unit]                = _ => AttributeValue.builder().m(Map.empty[String, AttributeValue].asJava).build()
  implicit val uuidEncoder: D4SAttributeEncoder[UUID]                = n => AttributeValue.builder().s(n.toString).build()
  implicit val bytesEncoder: D4SAttributeEncoder[Array[Byte]]        = n => AttributeValue.builder().b(SdkBytes.fromByteArray(n)).build()
  implicit val sdkBytesEncoder: D4SAttributeEncoder[SdkBytes]        = n => AttributeValue.builder().b(n).build()

  implicit val binarySetSdkBytesEncoder: D4SAttributeEncoder[Set[SdkBytes]] =
    item => AttributeValue.builder().bs(item.asJavaCollection).build()

  implicit val binarySetEncoder: D4SAttributeEncoder[Set[Array[Byte]]] =
    item => AttributeValue.builder().bs(item.map(SdkBytes.fromByteArray).asJavaCollection).build()

  implicit val stringSetEncoder: D4SAttributeEncoder[Set[String]] =
    item => AttributeValue.builder().ss(item.asJavaCollection).build()

  implicit val byteSetEncoder: D4SAttributeEncoder[Set[Byte]]     = item => AttributeValue.builder().ns(item.map(_.toString).asJavaCollection).build()
  implicit val shortSetEncoder: D4SAttributeEncoder[Set[Short]]   = item => AttributeValue.builder().ns(item.map(_.toString).asJavaCollection).build()
  implicit val intSetEncoder: D4SAttributeEncoder[Set[Int]]       = item => AttributeValue.builder().ns(item.map(_.toString).asJavaCollection).build()
  implicit val longSetEncoder: D4SAttributeEncoder[Set[Long]]     = item => AttributeValue.builder().ns(item.map(_.toString).asJavaCollection).build()
  implicit val floatSetEncoder: D4SAttributeEncoder[Set[Float]]   = item => AttributeValue.builder().ns(item.map(_.toString).asJavaCollection).build()
  implicit val doubleSetEncoder: D4SAttributeEncoder[Set[Double]] = item => AttributeValue.builder().ns(item.map(_.toString).asJavaCollection).build()

  implicit def iterableEncoder[L[_], T: D4SAttributeEncoder](implicit ev: L[T] <:< Iterable[T]): D4SAttributeEncoder[L[T]] = {
    item: L[T] =>
      val ls = item.map(encode[T])
      AttributeValue.builder().l(ls.asJavaCollection).build()
  }

  implicit def mapLikeEncoder[M[k, v] <: scala.collection.Map[k, v], K: D4SKeyEncoder, V: D4SAttributeEncoder]: D4SEncoder[M[K, V]] = {
    _.map { case (k, v) => D4SKeyEncoder.encode(k) -> encode(v) }.toMap
  }

  implicit def optionEncoder[T: D4SAttributeEncoder]: D4SAttributeEncoder[Option[T]] = {
    item: Option[T] =>
      item.map(encode[T]).getOrElse(AttributeValue.builder().nul(true).build())
  }

  implicit def eitherEncoder[A: D4SAttributeEncoder, B: D4SAttributeEncoder]: D4SEncoder[Either[A, B]] = D4SEncoder.derived

  private[this] def numericAttributeEncoder[NumericType]: D4SAttributeEncoder[NumericType] = n => AttributeValue.builder().n(n.toString).build()

  private[this] final class CaseObjectEncoder[T](name: String) extends D4SAttributeEncoder[T] {
    override def encode(item: T): AttributeValue = AttributeValue.builder().s(name).build()
  }
}
