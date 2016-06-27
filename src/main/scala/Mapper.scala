package nescala

import helpers.Unsigned._

trait Mapper {
  def Mirror:Int
  def Read(address:Int): Int
  def Write(address:Int, value: Int)
  def Step(ppuCycle:Long, ppuScanLine:Long, ppuFlagShowBackground:Int, ppuFlagShowSprites:Int, triggerIRQHandler: => Unit) : Unit = ()
  val sRamAddress = 0x6000
  val isChr: Int => Boolean = x => x < 0x2000
  val isPrg1: Int => Boolean = x => x >= 0x8000
  val isPrg2:Int => Boolean = x => x >= 0xC000
  val isSRam: Int => Boolean = x => x >= sRamAddress
}

object MirrorMode {
  val Horizontal = 0
  val Vertical   = 1
  val Single0    = 2
  val Single1    = 3
  val Four       = 4
}

object Mapper {
  def apply(cartridge:Cartridge): Mapper = {
    cartridge.Mapper match {
      case 0 | 2 | 71 => Mapper2(cartridge.Mirror, cartridge.ChrRom, cartridge.PrgRom, cartridge.SRam)
      case 1          => Mapper1(cartridge.Mirror, cartridge.ChrRom, cartridge.PrgRom, cartridge.SRam)
      case 3          => Mapper3(cartridge.Mirror, cartridge.ChrRom, cartridge.PrgRom, cartridge.SRam)
      case 4          => Mapper4(cartridge.Mirror, cartridge.ChrRom, cartridge.PrgRom, cartridge.SRam)
      case 7          => Mapper7(cartridge.Mirror, cartridge.ChrRom, cartridge.PrgRom, cartridge.SRam)
      case 9          => Mapper9(cartridge.Mirror, cartridge.ChrRom, cartridge.PrgRom, cartridge.SRam)
      case unsupported => throw new Exception(s"Unhandled mapper: $unsupported")
    }
  }
}

case class Mapper1(mirror:Int, chrRom:Array[Int], prgRom:Array[Int], sRam:Array[Int]) extends Mapper {
  private val resetShiftRegister = 0x10
  private val prgOffsets = Array(0, prgBankOffset(-1))
  private val chrOffsets = Array(0, 0)

  private var shiftRegister: Int = resetShiftRegister
  private var mirrorMode = mirror
  private var prgMode, chrMode, prgBank, chrBank0, chrBank1, control: Int = 0

  def Read(address: Int): Int = address match {
    case chr if isChr(address) =>
      val bank = chr / 0x1000
      val offset = chr % 0x1000
      chrRom(chrOffsets(bank) + offset)
    case prg if isPrg1(address) =>
      val loadAddress = (address - 0x8000) as uShort
      val bank = loadAddress / 0x4000
      val offset = loadAddress % 0x4000
      prgRom(prgOffsets(bank) + offset)
    case saveRam if isSRam(address) => sRam(address - 0x6000)
    case default => throw new IndexOutOfBoundsException(s"Unhandled mapper1 read at address: ${Integer.toHexString(default)}")
  }

  override def Write(address: Int, value: Int): Unit = address match {
    case chr if isChr(address) =>
      val bank = chr / 0x1000
      val offset = chr % 0x1000
      chrRom(chrOffsets(bank) + offset) = value
    case prg if isPrg1(address) => loadRegister(prg, value)
    case saveRam if isSRam(address) => sRam(saveRam - sRamAddress) = value
    case default => throw new IndexOutOfBoundsException(s"Unhandled mapper1 write at address: ${Integer.toHexString(default)}")
  }

  override def Mirror: Int = mirrorMode

  private def loadRegister(address: Int, value: Int) = {
    if ((value & 0x80) == 0x80) {
      shiftRegister = resetShiftRegister
      writeControl(control | 0x0C)
    } else {
      val complete = (shiftRegister & 1) == 1
      shiftRegister = shiftRegister >>> 1
      shiftRegister = (shiftRegister | ((value & 1) << 4)) as uByte
      if (complete) {
        writeRegister(address, shiftRegister)
        shiftRegister = resetShiftRegister
      }
    }
  }

  private def writeRegister(address: Int, value: Int) = address match {
      case controlSpace if address <= 0x9FFF => writeControl(value)
      case chr0Space if address <= 0xBFFF => writeCHRBank0(value)
      case chr1Space if address <= 0xDFFF => writeCHRBank1(value)
      case prgSpace if address <= 0xFFFF => writePRGBank(value)
  }

  // Control (internal, $8000-$9FFF)
  private def writeControl(value: Int) = {
    control = value
    chrMode = (value >>> 4) & 1
    prgMode = (value >>> 2) & 3
    mirrorMode = value & 3 match {
                case 0 => MirrorMode.Single0
                case 1 => MirrorMode.Single1
                case 2 => MirrorMode.Vertical
                case 3 => MirrorMode.Horizontal
    }
    updateOffsets()
  }

  // CHR bank 0 (internal, $A000-$BFFF)
  private def writeCHRBank0(x: Int):Unit = {
    chrBank0 = x
    updateOffsets()
  }

  // CHR bank 1 (internal, $C000-$DFFF)
  private def writeCHRBank1(x: Int):Unit = {
    chrBank1 = x
    updateOffsets()
  }

  private def writePRGBank(x: Int):Unit = {
    prgBank = x & 0x0F
    updateOffsets()
  }

  private def chrBankOffset(index: Int): Int = {
    val bankLength = chrRom.length
    val y = if (index >= 0x80) index - 0x100 else index
    val offset = findOffset(y, bankLength, 0x1000)

    if (offset < 0) offset + bankLength
    else offset
  }

  private def prgBankOffset(index: Int): Int = {
    val bankLength = prgRom.length
    val y = if (index >= 0x80) index - 0x100 else index
    val offset = findOffset(y, bankLength, 0x4000)

    if (offset < 0) offset + bankLength
    else offset
  }

  private def findOffset(index: Int, bankLength: Int, location: Int) = (index % (bankLength / location)) * location

  // PRG ROM bank mode (0, 1: switch 32 KB at $8000, ignoring low bit of bank number;
  //                    2: fix first bank at $8000 and switch 16 KB bank at $C000;
  //                    3: fix last bank at $C000 and switch 16 KB bank at $8000)
  // CHR ROM bank mode (0: switch 8 KB at a time; 1: switch two separate 4 KB banks)
  private def updateOffsets() = {
    prgMode match {
      case 0 | 1 =>
        prgOffsets(0) = prgBankOffset(prgBank & 0xFE)
        prgOffsets(1) = prgBankOffset(prgBank | 0x01)
      case 2 =>
        prgOffsets(0) = 0
        prgOffsets(1) = prgBankOffset(prgBank)
      case 3 =>
        prgOffsets(0) = prgBankOffset(prgBank)
        prgOffsets(1) = prgBankOffset(-1)
    }

    chrMode match {
      case 0 =>
        chrOffsets(0) = chrBankOffset(chrBank0 & 0xFE)
        chrOffsets(1) = chrBankOffset(chrBank0 | 0x01)
      case 1 =>
        chrOffsets(0) = chrBankOffset(chrBank0)
        chrOffsets(1) = chrBankOffset(chrBank1)
    }
  }
}

case class Mapper2(mirror:Int, chrRom:Array[Int], prgRom:Array[Int], sRam:Array[Int]) extends Mapper {
  var prgBanks = prgRom.length / 0x4000
  var prgBank1 = 0
  var prgBank2 = prgBanks - 1

  override def Read(address: Int): Int = address match {
    case chr if isChr(address) => chrRom(address)
    case prg2 if isPrg2(address) =>
      val index = prgBank2 * 0x4000 + (prg2 - 0xC000)
      prgRom(index)
    case prg1 if isPrg1(address) =>
      val index = prgBank1 * 0x4000 + (prg1 - 0x8000)
      prgRom(index)
    case saveRam if isSRam(address) =>
      val index = address - sRamAddress
      sRam(index)
    case default => System.err.println(s"Unhandled mapper2 read at address: ${Integer.toHexString(default)}"); throw new Exception
  }

  override def Write(address: Int, value: Int): Unit = address match {
    case chr if isChr(address) => chrRom(address) = value
    case prg1 if isPrg1(address) => prgBank1 = value % prgBanks
    case saveRam if isSRam(address) =>
      val index = address - sRamAddress
      sRam(index) = value
    case default => System.err.println(s"Unhandled mapper2 write at address: ${Integer.toHexString(default)}")
  }

  override def Mirror: Int = mirror
}

case class Mapper3(var mirror:Int, chrRom:Array[Int], prgRom:Array[Int], sRam:Array[Int]) extends Mapper {

  var prgBanks = prgRom.length / 0x4000
  var chrBank  = 0
  var prgBank1 = 0
  var prgBank2 = prgBanks - 1

  override def Read(address:Int): Int =  address match {
      case chr if isChr(address) =>
        val index = chrBank * 0x2000 + address
        chrRom(index)
      case prg2 if isPrg2(address) =>
        val index = prgBank2 * 0x4000 + (address - 0xC000)
        prgRom(index)
      case prg1 if isPrg1(address) =>
        val index = prgBank1 * 0x4000 + (address - 0x8000)
        prgRom(index)
      case saveRam if address >= 0x6000 =>
        val index = (address - 0x6000) as uShort
        sRam(index)
      case default => System.err.println(s"Unhandled mapper3 write at address: ${Integer.toHexString(default)}"); 0
  }

  override def Write(address: Int, value: Int): Unit = address match {
    case chr if isChr(address) =>
      val index = chrBank * 0x2000 + address
      chrRom(index) = value
    case prg1 if isPrg1(address) => chrBank = value & 3
    case saveRam if isSRam(address) =>
        val index = address - 0x6000
        sRam(index) = value
    case default => System.err.println(s"Unhandled mapper3 read at address: ${Integer.toHexString(default)}"); throw new Exception
  }

  override def Mirror: Int = mirror
}

case class Mapper4(mirror:Int, chrRom:Array[Int], prgRom:Array[Int], sRam:Array[Int]) extends Mapper {

  private val prgOffsets = Array(prgBankOffset(0), prgBankOffset(1), prgBankOffset(-2), prgBankOffset(-1))
  private val chrOffsets = Array.fill[Int](8)(0)
  private val registers = Array.fill[Int](8)(0)

  private var register, prgMode, chrMode, reload, counter    = 0
  private var irqEnable  = false
  private var mirrorMode = mirror

  private def prgBankOffset(index:Int): Int = {
    var i = index
    if (i >= 0x80) i -= 0x100
    i %= prgRom.length / 0x2000
    var offset = i * 0x2000
    if (offset < 0) offset += prgRom.length
    offset
  }

  override def Read(address:Int): Int =  address match {
    case chr if isChr(address) =>
      val bank = address / 0x0400
      val offset = address % 0x0400
      chrRom(chrOffsets(bank) + offset)
    case prg1 if isPrg1(address) =>
      val addressOffset = (address - 0x8000) as uShort
      val bank = addressOffset / 0x2000
      val offset = addressOffset % 0x2000
      prgRom(prgOffsets(bank) + offset)
    case saveRam if address >= 0x6000 =>
      val index = (address - 0x6000) as uShort
      sRam(index)
    case default => System.err.println(s"Unhandled mapper4 write at address: ${Integer.toHexString(default)}"); 0
  }

  override def Write(address: Int, value: Int): Unit = address match {
    case chr if isChr(address) =>
     val bank = address / 0x0400
     val offset = address % 0x0400
     chrRom(chrOffsets(bank + offset)) = value
    case prg1 if isPrg1(address) => writeRegister(address, value)
    case saveRam if isSRam(address) =>
      val index = address - 0x6000
      sRam(index) = value
    case default => System.err.println(s"Unhandled mapper4 read at address: ${Integer.toHexString(default)}"); throw new Exception
  }

  override def Mirror: Int = mirrorMode

  private def writeRegister(address:Int, value:Int) = address match {
      case bankSelect if address <= 0x9FFF && address % 2 == 0 => writeBankSelect(value)
      case bankData   if address <= 0x9FFF && address % 2 == 1 => writeBankData(value)
      case setMirror  if address <= 0xBFFF && address % 2 == 0 => writeMirror(value)
      case protect    if address <= 0xBFFF && address % 2 == 1 => writeProtect(value)
      case irqLatch   if address <= 0xDFFF && address % 2 == 0 => writeIRQLatch(value)
      case reloadIrq  if address <= 0xDFFF && address % 2 == 1 => writeIRQReload(value)
      case disableIrq if address <= 0xFFFF && address % 2 == 0 => writeIRQDisable(value)
      case enableIrq  if address <= 0xFFFF && address % 2 == 1 => writeIRQEnable(value)
  }

  private def writeBankSelect(value:Int) = {
    prgMode = (value >> 6) & 1
    chrMode = (value >> 7) & 1
    register = value & 7
    updateOffsets()
  }

  private def writeBankData(value:Int) = {
    registers(register) = value
    updateOffsets()
  }

  private def writeMirror(value:Int) = value & 1 match {
      case 0 => mirrorMode = MirrorMode.Vertical
      case 1 => mirrorMode = MirrorMode.Horizontal
  }

  private def writeProtect(value:Int) = ()

  private def writeIRQLatch(value:Int) = reload = value

  private def writeIRQReload(value:Int) = counter = 0

  private def writeIRQDisable(value:Int) = irqEnable = false

  private def writeIRQEnable(value:Int) = irqEnable = true

  private def chrBankOffset(index:Int): Int = {
    var i = index
    if (i >= 0x80) {
      i -= 0x100
    }
    i %= chrRom.length / 0x0400
    var offset = index * 0x0400
    if (offset < 0) {
      offset += chrRom.length
    }
    offset
  }

  private def updateOffsets() = {

    prgMode match {
      case 0 =>
        prgOffsets(0) = prgBankOffset(registers(6))
        prgOffsets(1) = prgBankOffset(registers(7))
        prgOffsets(2) = prgBankOffset(-2)
        prgOffsets(3) = prgBankOffset(-1)
      case 1 =>
        prgOffsets(0) = prgBankOffset(-2)
        prgOffsets(1) = prgBankOffset(registers(7))
        prgOffsets(2) = prgBankOffset(registers(6))
        prgOffsets(3) = prgBankOffset(-1)
    }

    chrMode match {
      case 0 =>
        chrOffsets(0) = chrBankOffset(registers(0) & 0xFE)
        chrOffsets(1) = chrBankOffset(registers(0) | 0x01)
        chrOffsets(2) = chrBankOffset(registers(1) & 0xFE)
        chrOffsets(3) = chrBankOffset(registers(1) | 0x01)
        chrOffsets(4) = chrBankOffset(registers(2))
        chrOffsets(5) = chrBankOffset(registers(3))
        chrOffsets(6) = chrBankOffset(registers(4))
        chrOffsets(7) = chrBankOffset(registers(5))
      case 1 =>
       chrOffsets(0) = chrBankOffset(registers(2))
       chrOffsets(1) = chrBankOffset(registers(3))
       chrOffsets(2) = chrBankOffset(registers(4))
       chrOffsets(3) = chrBankOffset(registers(5))
       chrOffsets(4) = chrBankOffset(registers(0) & 0xFE)
       chrOffsets(5) = chrBankOffset(registers(0) | 0x01)
       chrOffsets(6) = chrBankOffset(registers(1) & 0xFE)
       chrOffsets(7) = chrBankOffset(registers(1) | 0x01)
    }
  }

  override def Step(ppuCycle:Long, ppuScanLine:Long, ppuFlagShowBackground:Int, ppuFlagShowSprites:Int, triggerIRQHandler: => Unit):Unit = {

    if (ppuCycle != 280) return
    if (ppuScanLine > 239 && ppuScanLine < 261) return
    if (ppuFlagShowBackground == 0 && ppuFlagShowSprites == 0) return
    handleScanLine(triggerIRQHandler)
  }


  private def handleScanLine(triggerIRQHandler: => Unit) {
    if (counter == 0) counter = reload
    else {
      counter = (counter - 1) as uByte
      if (counter == 0 && irqEnable) triggerIRQHandler
    }
  }
}

case class Mapper7(mirror:Int, chrRom:Array[Int], prgRom:Array[Int], sRam:Array[Int]) extends Mapper {
  private var prgBank = 0
  private var mirrorMode = mirror

  override def Read(address: Int): Int = address match {
    case chr if isChr(address) => chrRom(address)
    case prg1 if isPrg1(address) =>
      val index = prgBank * 0x8000 + (prg1 - 0x8000)
      prgRom(index)
    case saveRam if isSRam(address) =>
      val index = address - sRamAddress
      sRam(index)
    case default => System.err.println(s"Unhandled mapper7 read at address: ${Integer.toHexString(default)}"); throw new Exception
  }

  override def Write(address: Int, value: Int): Unit = address match {
    case chr if isChr(address) => chrRom(address) = value
    case prg1 if isPrg1(address) =>
      prgBank = value & 7
       value & 0x10 match {
        case 0x00 => mirrorMode = MirrorMode.Single0
        case 0x10 => mirrorMode = MirrorMode.Single1
       }
    case saveRam if isSRam(address) =>
      val index = address - sRamAddress
      sRam(index) = value
    case default => System.err.println(s"Unhandled mapper7 write at address: ${Integer.toHexString(default)}")
  }

  override def Mirror: Int = mirrorMode
}

case class Mapper9(mirror:Int, chrRom:Array[Int], prgRom:Array[Int], sRam:Array[Int]) extends Mapper {

  private val prgBanks = prgRom.length / 0x2000

  private val chrOffsets = Array.fill[Int](4)(0)
  private var prgBank1 = 0
  private val prgBank2 = prgBanks - 3
  private var latch0, latch1    = 0xFD
  private var mirrorMode = mirror

  override def Read(address: Int): Int = address match {
    case chr1 if address < 0x1000 =>
      val bank = if(latch0 == 0xFD) chrOffsets(0) else chrOffsets(1)
      setLatch(chr1)
      val index = (bank * 0x1000) + chr1
      chrRom(index)
    case chr2 if isChr(address) =>
      val bank = if(latch1 == 0xFD) chrOffsets(2) else chrOffsets(3)
      setLatch(chr2)
      val index = (bank * 0x1000) + chr2 - 0x1000
      chrRom(index)
    case prg2 if address >= 0xA000 =>
      val index = prgBank2 * 0x2000 + (prg2 - 0xA000)
      prgRom(index)
    case prg1 if isPrg1(address) =>
      val index = prgBank1 * 0x2000 + (prg1 - 0x8000)
      prgRom(index)
    case saveRam if isSRam(address) =>
      val index = address - sRamAddress
      sRam(index)
    case default => System.err.println(s"Unhandled mapper9 read at address: ${Integer.toHexString(default)}"); throw new Exception
  }

  override def Write(address: Int, value: Int): Unit = address match {
    case mirror1 if address >= 0xF000 => value & 0x1 match {
      case 0 => mirrorMode = MirrorMode.Vertical
      case 1 => mirrorMode = MirrorMode.Horizontal
    }
    case chr1   if address >= 0xE000 => chrOffsets(3) = value & 0x1F
    case chr1   if address >= 0xD000 => chrOffsets(2) = value & 0x1F
    case chr2   if address >= 0xC000 => chrOffsets(1) = value & 0x1F
    case chr2   if address >= 0xB000 => chrOffsets(0) = value & 0x1F
    case prg1   if address >= 0xA000 => prgBank1 = value & 0xF
    case saveRam if isSRam(address) =>
      val index = address - sRamAddress
      sRam(index) = value
    case default => System.err.println(s"Unhandled mapper9 write at address: ${Integer.toHexString(default)}")
  }

  override def Mirror: Int = mirrorMode

  private def setLatch(address: Int): Unit = address match {
    case bank2 if bank2 >= 0x1FE8 && bank2 <= 0x1FEF => latch1 = 0xFE
    case bank2 if bank2 >= 0x1FD8 && bank2 <= 0x1FDF => latch1 = 0xFD
    case bank1 if bank1 == 0x0FD8 => latch0 = 0xFD
    case bank1 if bank1 == 0x0FE8 => latch0 = 0xFE
    case default =>
  }

}