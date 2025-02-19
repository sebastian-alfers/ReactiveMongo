/*
 * Copyright 2012-2013 Stephane Godbillon (@sgodbillon) and Zenexity
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactivemongo.api.gridfs

import java.io.{ InputStream, OutputStream }

import java.util.Arrays

import java.security.MessageDigest

import scala.util.control.NonFatal

import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.ClassTag

import reactivemongo.core.errors.GenericDriverException

import reactivemongo.api.{
  Collection,
  CollectionStats,
  Cursor,
  CursorProducer,
  DB,
  DBMetaCommands,
  FailingCursor,
  PackSupport,
  ReadPreference,
  SerializationPack,
  Session
}
import reactivemongo.api.collections.QueryBuilderFactory
import reactivemongo.api.commands.{
  CollStats,
  Command,
  CommandCodecs,
  CommandCodecsWithPack,
  CommandException,
  Create,
  CreateCollection,
  DeleteCommand,
  InsertCommand,
  ResolvedCollectionCommand,
  UpdateCommand,
  UpdateWriteResultFactory,
  UpsertedFactory,
  WriteResult
}
import reactivemongo.api.gridfs.{ FileToSave => SF, ReadFile => RF }
import reactivemongo.api.indexes.{ Index, IndexType }

import IndexType.Ascending

/**
 * A GridFS store.
 *
 * @define findDescription Finds the files matching the given selector
 * @define fileSelector the query to find the files
 * @define readFileParam the file to be read
 * @define fileReader fileReader a file reader automatically resolved if `Id` is a valid value
 */
sealed trait GridFS[P <: SerializationPack]
    extends GridFSCompat
    with PackSupport[P]
    with InsertCommand[P]
    with DeleteCommand[P]
    with UpdateCommand[P]
    with UpdateWriteResultFactory[P]
    with UpsertedFactory[P]
    with CommandCodecsWithPack[P]
    with QueryBuilderFactory[P] { self =>

  /* The database where this store is located. */
  protected def db: DB with DBMetaCommands

  /*
   * The prefix of this store.
   * The `files` and `chunks` collections will be actually
   * named `\${prefix}.files` and `\${prefix}.chunks`.
   */
  protected def prefix: String

  private[reactivemongo] def session(): Option[Session] = db.session

  /* The `files` collection */
  private lazy val fileColl = new Collection {
    val db = self.db
    val name = s"${self.prefix}.files"
    val failoverStrategy = db.failoverStrategy
  }

  /* The `chunks` collection */
  private lazy val chunkColl = new Collection {
    val db = self.db
    val name = s"${self.prefix}.chunks"
    val failoverStrategy = db.failoverStrategy
  }

  private lazy val fileQueryBuilder = new QueryBuilder(
    collection = fileColl,
    failoverStrategy = db.failoverStrategy,
    readConcern = db.connection.options.readConcern,
    readPreference = db.defaultReadPreference
  )

  private lazy val chunkQueryBuilder = new QueryBuilder(
    collection = chunkColl,
    failoverStrategy = db.failoverStrategy,
    readConcern = db.connection.options.readConcern,
    readPreference = db.defaultReadPreference
  )

  private lazy val runner = Command.run[pack.type](pack, db.failoverStrategy)

  private lazy val builder = pack.newBuilder

  private lazy val decoder = pack.newDecoder

  import builder.{ document, elementProducer => elem }

  type ReadFile[+Id <: pack.Value] = RF[Id, pack.Document]

  type FileToSave[Id <: pack.Value] = SF[Id, pack.Document]

  @annotation.implicitNotFound("Cannot resolve a file reader: make sure Id type ${Id} is a serialized value (e.g. kind of BSON value) and that a ClassTag instance is implicitly available for")
  private[api] sealed trait FileReader[Id <: pack.Value] {
    def read(doc: pack.Document): ReadFile[Id]

    implicit lazy val reader: pack.Reader[ReadFile[Id]] =
      pack.reader[ReadFile[Id]](read(_))
  }

  private[api] object FileReader {

    implicit def default[Id <: pack.Value](
        implicit
        idTag: ClassTag[Id]
      ): FileReader[Id] = {
      val underlying = RF.reader[P, Id](pack)

      new FileReader[Id] {
        def read(doc: pack.Document) = pack.deserialize(doc, underlying)
      }
    }
  }

  /**
   * $findDescription.
   *
   * @param selector $fileSelector
   * @param r $fileReader
   *
   * @tparam S The type of the selector document. An implicit `Writer[S]` must be in the scope.
   * @tparam Id the type of the file ID to be read
   *
   * {{{
   * import scala.concurrent.ExecutionContext
   *
   * import reactivemongo.api.gridfs.GridFS
   *
   * import reactivemongo.api.bson.{ BSONDocument, BSONValue }
   * import reactivemongo.api.bson.collection.{ BSONSerializationPack => Pack }
   *
   * def foo(gfs: GridFS[Pack.type], n: String)(implicit ec: ExecutionContext) =
   *   gfs.find[BSONDocument, BSONValue](
   *     BSONDocument("filename" -> n)).headOption
   * }}}
   */
  def find[S, Id <: pack.Value](
      selector: S
    )(implicit
      w: pack.Writer[S],
      r: FileReader[Id],
      cp: CursorProducer[ReadFile[Id]]
    ): cp.ProducedCursor =
    try {
      val q = pack.serialize(selector, w)
      val query = fileQueryBuilder.filter(q)

      import r.reader

      query.cursor[ReadFile[Id]](defaultReadPreference)
    } catch {
      case NonFatal(cause) =>
        FailingCursor(db.connection, cause)
    }

  /**
   * $findDescription.
   *
   * @param selector $fileSelector
   * @param r $fileReader
   *
   * {{{
   * import scala.concurrent.ExecutionContext
   *
   * import reactivemongo.api.gridfs.GridFS
   *
   * import reactivemongo.api.bson.BSONDocument
   * import reactivemongo.api.bson.collection.{ BSONSerializationPack => Pack }
   *
   * def foo(gfs: GridFS[Pack.type], n: String)(implicit ec: ExecutionContext) =
   *   gfs.find(BSONDocument("filename" -> n)).headOption
   * }}}
   */
  def find(
      selector: pack.Document
    )(implicit
      cp: CursorProducer[ReadFile[pack.Value]]
    ): cp.ProducedCursor = {
    implicit def w: pack.Writer[pack.Document] = pack.IdentityWriter
    implicit def r: FileReader[pack.Value] = FileReader.default(pack.IsValue)

    find[pack.Document, pack.Value](selector)
  }

  /**
   * Returns a cursor for the chunks of the specified file.
   * The cursor walks the chunks orderly.
   *
   * @param file $readFileParam
   */
  def chunks(
      file: ReadFile[pack.Value],
      readPreference: ReadPreference = defaultReadPreference
    )(implicit
      cp: CursorProducer[Array[Byte]]
    ): cp.ProducedCursor = {
    val selectorOpts = chunkSelector(file)
    val sortOpts = document(Seq(elem("n", builder.int(1))))
    implicit def reader: pack.Reader[Array[Byte]] = chunkReader

    val query = chunkQueryBuilder.filter(selectorOpts).sort(sortOpts)

    query.cursor[Array[Byte]](readPreference)
  }

  /**
   * Reads the given file and writes its contents to the given OutputStream.
   *
   * @param file $readFileParam
   */
  def readToOutputStream[Id <: pack.Value](
      file: ReadFile[Id],
      out: OutputStream,
      readPreference: ReadPreference = defaultReadPreference
    )(implicit
      ec: ExecutionContext
    ): Future[Unit] = {
    val selectorOpts = chunkSelector(file)
    val sortOpts = document(Seq(elem("n", builder.int(1))))
    val query = chunkQueryBuilder.filter(selectorOpts).sort(sortOpts)

    implicit def r: pack.Reader[pack.Document] = pack.IdentityReader

    val cursor = query.cursor[pack.Document](readPreference)

    @inline def pushChunk(doc: pack.Document): Cursor.State[Unit] =
      decoder.binary(doc, "data") match {
        case Some(array) =>
          Cursor.Cont(out write array)

        case _ => {
          val errmsg =
            s"not a chunk! failed assertion: data field is missing: ${pack pretty doc}"

          logger.error(errmsg)
          Cursor.Fail(new GenericDriverException(errmsg))
        }
      }

    cursor.foldWhile({})((_, doc) => pushChunk(doc), Cursor.FailOnError())
  }

  /** Writes the data provided by the given InputStream to the given file. */
  def writeFromInputStream[Id <: pack.Value](
      file: FileToSave[Id],
      input: InputStream,
      chunkSize: Int = 262144
    )(implicit
      ec: ExecutionContext
    ): Future[ReadFile[Id]] = {
    type M = MessageDigest

    lazy val digestInit = MessageDigest.getInstance("MD5")

    def digestUpdate(md: MessageDigest, chunk: Array[Byte]) = {
      md.update(chunk); md
    }

    def digestFinalize(md: MessageDigest) = Future(md.digest()).map(Some(_))

    case class Chunk(
        previous: Array[Byte],
        n: Int,
        md: M,
        length: Int) {
      def feed(chunk: Array[Byte]): Future[Chunk] = {
        val wholeChunk = self.concat(previous, chunk)

        val normalizedChunkNumber = wholeChunk.length / chunkSize

        logger.debug(
          s"wholeChunk size is ${wholeChunk.length} => ${normalizedChunkNumber}"
        )

        val zipped =
          for (i <- 0 until normalizedChunkNumber)
            yield Arrays.copyOfRange(
              wholeChunk,
              i * chunkSize,
              (i + 1) * chunkSize
            ) -> i

        val left = Arrays.copyOfRange(
          wholeChunk,
          normalizedChunkNumber * chunkSize,
          wholeChunk.length
        )

        Future.traverse(zipped) { ci => writeChunk(n + ci._2, ci._1) }.map {
          _ =>
            logger.debug("all futures for the last given chunk are redeemed.")
            Chunk(
              if (left.isEmpty) Array.empty else left,
              n + normalizedChunkNumber,
              digestUpdate(md, chunk),
              length + chunk.length
            )
        }
      }

      import reactivemongo.util

      @inline def finish(): Future[ReadFile[Id]] =
        digestFinalize(md).map(_.map(util.hex2Str)).flatMap { md5Hex =>
          finalizeFile[Id](file, previous, n, chunkSize, length.toLong, md5Hex)
        }

      @inline def writeChunk(cn: Int, bytes: Array[Byte]) =
        self.writeChunk(file.id, cn, bytes)
    }

    val buffer = Array.ofDim[Byte](chunkSize)

    @SuppressWarnings(Array("VariableShadowing"))
    def go(previous: Chunk): Future[Chunk] =
      Future(input read buffer).flatMap {
        case n if n > 0 => {
          logger.debug(s"Processing new chunk from n=${previous.n}...\n")

          previous.feed(buffer take n).flatMap(go)
        }

        case _ =>
          Future.successful(previous)

      }

    go(Chunk(Array.empty, 0, digestInit, 0)).flatMap(_.finish())
  }

  protected lazy val maxWireVersion =
    db.connectionState.metadata.maxWireVersion

  /**
   * Updates the metadata document for the specified file.
   *
   * @param id the id of the file to be updated
   * @param metadata the file new metadata
   */
  def update[Id <: pack.Value](
      id: Id,
      metadata: pack.Document
    )(implicit
      ec: ExecutionContext
    ): Future[WriteResult] = {
    val updateFileCmd = new Update(
      firstUpdate = new UpdateElement(
        q = document(Seq(elem("_id", id))),
        u = Left(metadata),
        upsert = false,
        multi = false,
        collation = None,
        arrayFilters = Seq.empty
      ),
      updates = Seq.empty,
      ordered = false,
      writeConcern = defaultWriteConcern,
      bypassDocumentValidation = false
    )

    runner(fileColl, updateFileCmd, defaultReadPreference)
  }

  /**
   * Removes a file from this store.
   * Note that if the file does not actually exist,
   * the returned future will not be hold an error.
   *
   * @param id the file id to remove from this store
   */
  def remove[Id <: pack.Value](
      id: Id
    )(implicit
      ec: ExecutionContext
    ): Future[WriteResult] = {
    val deleteChunkCmd = new Delete(
      Seq(new DeleteElement(_q = document(Seq(elem("files_id", id))), 0, None)),
      ordered = false,
      writeConcern = defaultWriteConcern
    )

    val deleteFileCmd = new Delete(
      Seq(new DeleteElement(_q = document(Seq(elem("_id", id))), 1, None)),
      ordered = false,
      writeConcern = defaultWriteConcern
    )

    for {
      _ <- runner(chunkColl, deleteChunkCmd, defaultReadPreference)
      r <- runner(fileColl, deleteFileCmd, defaultReadPreference)
    } yield r
  }

  /**
   * Creates the needed indexes on the GridFS collections
   * (`chunks` and `files`).
   *
   * Please note that you should really consider reading
   * [[http://www.mongodb.org/display/DOCS/Indexes]] before doing this,
   * especially in production.
   *
   * @return A future containing true if the index was created, false if it already exists.
   */
  @SuppressWarnings(Array("VariableShadowing"))
  def ensureIndex()(implicit ec: ExecutionContext): Future[Boolean] = {
    val indexMngr = db.indexesManager[P](pack)(ec)

    for {
      _ <- create(chunkColl)
      c <- indexMngr
        .onCollection(chunkColl.name)
        .ensure(
          Index(pack)(
            key = List("files_id" -> Ascending, "n" -> Ascending),
            name = None,
            unique = true,
            background = false,
            sparse = false,
            expireAfterSeconds = None,
            storageEngine = None,
            weights = None,
            defaultLanguage = None,
            languageOverride = None,
            textIndexVersion = None,
            sphereIndexVersion = None,
            bits = None,
            min = None,
            max = None,
            bucketSize = None,
            collation = None,
            wildcardProjection = None,
            version = None, // let MongoDB decide
            partialFilter = None,
            options = builder.document(Seq.empty)
          )
        )

      _ <- create(fileColl)
      f <- indexMngr
        .onCollection(fileColl.name)
        .ensure(
          Index(pack)(
            key = List("filename" -> Ascending, "uploadDate" -> Ascending),
            name = None,
            unique = false,
            background = false,
            sparse = false,
            expireAfterSeconds = None,
            storageEngine = None,
            weights = None,
            defaultLanguage = None,
            languageOverride = None,
            textIndexVersion = None,
            sphereIndexVersion = None,
            bits = None,
            min = None,
            max = None,
            bucketSize = None,
            collation = None,
            wildcardProjection = None,
            version = None, // let MongoDB decide
            partialFilter = None,
            options = builder.document(Seq.empty)
          )
        )
    } yield (c && f)
  }

  /**
   * Returns whether the data related to this GridFS instance
   * exists on the database.
   */
  @SuppressWarnings(Array("VariableShadowing"))
  def exists(implicit ec: ExecutionContext): Future[Boolean] = (for {
    _ <- stats(chunkColl).filter { c => c.size > 0 || c.nindexes > 0 }
    _ <- stats(fileColl).filter { f => f.size > 0 || f.nindexes > 0 }
  } yield true).recover { case _ => false }

  // Dependent factories

  /**
   * Prepare the information to save a file.
   * The unique ID is automatically generated.
   */
  def fileToSave(
      filename: Option[String] = None,
      contentType: Option[String] = None,
      uploadDate: Option[Long] = None,
      metadata: pack.Document = document(Seq.empty)
    ): FileToSave[pack.Value] =
    new FileToSave[pack.Value](
      filename = filename,
      contentType = contentType,
      uploadDate = uploadDate,
      metadata = metadata,
      id = builder.generateObjectId()
    )

  /** Prepare the information to save a file. */
  def fileToSave[Id <: pack.Value](
      filename: Option[String],
      contentType: Option[String],
      uploadDate: Option[Long],
      metadata: pack.Document,
      id: Id
    ): FileToSave[Id] =
    new FileToSave[Id](
      filename = filename,
      contentType = contentType,
      uploadDate = uploadDate,
      metadata = metadata,
      id = id
    )

  // ---

  private[reactivemongo] def writeChunk(
      id: pack.Value,
      n: Int,
      bytes: Array[Byte]
    )(implicit
      ec: ExecutionContext
    ): Future[WriteResult] = {
    logger.debug(s"Writing chunk #$n @ file $id")

    val chunkDoc = document(
      Seq(
        elem("files_id", id),
        elem("n", builder.int(n)),
        elem("data", builder.binary(bytes))
      )
    )

    val insertChunkCmd = new Insert(
      head = chunkDoc,
      tail = Seq.empty[pack.Document],
      ordered = false,
      writeConcern = defaultWriteConcern,
      bypassDocumentValidation = false
    )

    runner(chunkColl, insertChunkCmd, defaultReadPreference)
  }

  private[reactivemongo] def finalizeFile[Id <: pack.Value](
      file: FileToSave[Id],
      previous: Array[Byte],
      n: Int,
      chunkSize: Int,
      length: Long,
      md5Hex: Option[String]
    )(implicit
      ec: ExecutionContext
    ): Future[ReadFile[Id]] = {

    logger.debug(s"Writing last chunk #$n @ file ${file.id}")

    val uploadDate = file.uploadDate.getOrElse(System.nanoTime() / 1000000)
    val fileProps = Seq.newBuilder[pack.ElementProducer]

    fileProps ++= Seq(
      elem("_id", file.id),
      elem("chunkSize", builder.int(chunkSize)),
      elem("length", builder.long(length)),
      elem("uploadDate", builder.dateTime(uploadDate)),
      elem("metadata", file.metadata)
    )

    file.filename.foreach { fn =>
      fileProps += elem("filename", builder.string(fn))
    }

    file.contentType.foreach { ct =>
      fileProps += elem("contentType", builder.string(ct))
    }

    md5Hex.foreach { hex => fileProps += elem("md5", builder.string(hex)) }

    writeChunk(file.id, n, previous).flatMap { _ =>
      val fileDoc = document(fileProps.result())

      val insertFileCmd = new Insert(
        head = fileDoc,
        tail = Seq.empty[pack.Document],
        ordered = false,
        writeConcern = defaultWriteConcern,
        bypassDocumentValidation = false
      )

      @SuppressWarnings(Array("VariableShadowing"))
      @inline def run =
        runner(fileColl, insertFileCmd, defaultReadPreference).map { _ =>
          new ReadFile[Id](
            id = file.id,
            contentType = file.contentType,
            filename = file.filename,
            uploadDate = file.uploadDate,
            chunkSize = chunkSize,
            length = length,
            metadata = file.metadata,
            md5 = md5Hex
          )
        }

      run
    }
  }

  @inline private def chunkSelector[Id <: pack.Value](
      file: ReadFile[Id]
    ): pack.Document =
    document(
      Seq(
        elem("files_id", file.id),
        elem(
          "n",
          document(
            Seq(
              elem(f"$$gte", builder.int(0)),
              elem(
                f"$$lte",
                builder.long(
                  file.length / file.chunkSize + (if (
                                                    file.length % file.chunkSize > 0
                                                  ) 1
                                                  else 0)
                )
              )
            )
          )
        )
      )
    )

  private lazy val chunkReader: pack.Reader[Array[Byte]] = {
    val dec = pack.newDecoder

    pack.readerOpt[Array[Byte]] { doc => dec.binary(doc, "data") }
  }

  @inline private[reactivemongo] def defaultReadPreference =
    db.defaultReadPreference

  @inline private def defaultWriteConcern = db.connection.options.writeConcern

  // Coll creation

  private lazy val createCollCmd = Create()

  private implicit lazy val unitReader: pack.Reader[Unit] =
    CommandCodecs.unitReader[pack.type](pack)

  private implicit lazy val createWriter: pack.Writer[ResolvedCollectionCommand[Create]] =
    CreateCollection.writer[pack.type](pack)

  private def create(coll: Collection)(implicit ec: ExecutionContext) =
    runner(coll, createCollCmd, defaultReadPreference).recover {
      case CommandException.Code(48 /* already exists */ ) => ()

      case CommandException.Message("collection already exists") => ()
    }

  // Coll stats

  private lazy val collStatsCmd = new CollStats()

  private implicit lazy val collStatsWriter: pack.Writer[ResolvedCollectionCommand[CollStats]] =
    CollStats.writer[pack.type](pack)

  private implicit lazy val collStatsReader: pack.Reader[CollectionStats] =
    CollStats.reader[pack.type](pack)

  @inline private def stats(coll: Collection)(implicit ec: ExecutionContext) =
    runner(coll, collStatsCmd, defaultReadPreference)

  // ---

  override def toString: String =
    s"GridFS(db = ${db.name}, files = ${fileColl.name}, chunks = ${chunkColl.name})"
}

object GridFS {

  @SuppressWarnings(Array("VariableShadowing"))
  private[api] def apply[P <: SerializationPack](
      _pack: P,
      db: DB with DBMetaCommands,
      prefix: String
    ): GridFS[P] = {
    @SuppressWarnings(Array("MethodNames")) def _prefix = prefix
    @SuppressWarnings(Array("MethodNames")) def _db = db

    new GridFS[P] {
      val db = _db
      val prefix = _prefix
      val pack: P = _pack
    }
  }
}
